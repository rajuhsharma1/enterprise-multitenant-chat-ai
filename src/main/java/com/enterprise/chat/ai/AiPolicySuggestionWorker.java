package com.enterprise.chat.ai;

import com.enterprise.chat.config.KafkaTopicConfig;
import com.enterprise.chat.domain.dto.ChatMessageEvent;
import com.enterprise.chat.security.*;
import com.enterprise.chat.service.TimedDocumentService;
import com.enterprise.chat.ws.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AiPolicySuggestionWorker {
  private final QdrantClient vectors;
  private final EmbeddingAndLlmClient ai;
  private final ChannelRbacValidator rbac;
  private final TimedDocumentService docs;
  private final RedisCrossRegionFanoutListener redis;
  private final HomeRegionBackboneRouter backbone;
  private final ObjectMapper json;
  private final String collection, region;

  public AiPolicySuggestionWorker(
      QdrantClient vectors,
      EmbeddingAndLlmClient ai,
      ChannelRbacValidator rbac,
      TimedDocumentService docs,
      RedisCrossRegionFanoutListener redis,
      HomeRegionBackboneRouter backbone,
      ObjectMapper json,
      @Value("${app.qdrant.collection:policies}") String collection,
      @Value("${app.region}") String region) {
    this.vectors = vectors;
    this.ai = ai;
    this.rbac = rbac;
    this.docs = docs;
    this.redis = redis;
    this.backbone = backbone;
    this.json = json;
    this.collection = collection;
    this.region = region;
  }

  private static Condition keyword(String key, String value) {
    return Condition.newBuilder()
        .setField(
            FieldCondition.newBuilder().setKey(key).setMatch(Match.newBuilder().setKeyword(value)))
        .build();
  }

  public static Filter permissionFilter(TenantPrincipal p, Instant now) {
    return Filter.newBuilder()
        .addMust(keyword("org_id", p.orgId().toString()))
        .addMust(keyword("status", "READY"))
        .addMust(
            Condition.newBuilder()
                .setField(
                    FieldCondition.newBuilder()
                        .setKey("allowed_dept_ids")
                        .setMatch(
                            Match.newBuilder()
                                .setKeywords(
                                    RepeatedStrings.newBuilder()
                                        .addAllStrings(
                                            p.departmentIds().stream()
                                                .map(UUID::toString)
                                                .toList())))))
        .addMust(
            Condition.newBuilder()
                .setField(
                    FieldCondition.newBuilder()
                        .setKey("min_role_rank")
                        .setRange(Range.newBuilder().setLte(p.role().rank()))))
        .addMust(
            Condition.newBuilder()
                .setField(
                    FieldCondition.newBuilder()
                        .setKey("access_expires_at_epoch")
                        .setRange(Range.newBuilder().setGt(now.getEpochSecond()))))
        .build();
  }

  @KafkaListener(topics = KafkaTopicConfig.AI, groupId = "ai-policy-suggestions")
  public void suggest(String payload) throws Exception {
    var e = json.readValue(payload, ChatMessageEvent.class);
    TenantContext.withOrg(
        e.orgId(),
        () -> {
          TenantPrincipal p;
          try {
            p = rbac.loadPrincipal(e.senderId());
          } catch (org.springframework.security.access.AccessDeniedException denied) {
            return null;
          }
          return TenantContext.with(
              p,
              () -> {
                try {
                  rbac.require(e.channelId());
                } catch (org.springframework.security.access.AccessDeniedException denied) {
                  return null;
                }
                if (!region.equals(p.homeRegion())) throw new SecurityException("Wrong AI cell");
                if (p.departmentIds().isEmpty()) return null;
                try {
                  var hits =
                      vectors
                          .searchAsync(
                              SearchPoints.newBuilder()
                                  .setCollectionName(collection)
                                  .addAllVector(ai.embed(e.content()))
                                  .setFilter(permissionFilter(p, Instant.now()))
                                  .setShardKeySelector(
                                      DocumentIngestionWorker.tenantShard(p.orgId()))
                                  .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true))
                                  .setLimit(6)
                                  .build())
                          .get(30, TimeUnit.SECONDS);
                  StringBuilder evidence = new StringBuilder();
                  Set<UUID> citations = new HashSet<>();
                  for (var hit : hits) {
                    UUID doc =
                        UUID.fromString(hit.getPayloadOrThrow("document_id").getStringValue());
                    try {
                      docs.accessible(doc);
                    } catch (org.springframework.security.access.AccessDeniedException denied) {
                      continue;
                    }
                    citations.add(doc);
                    evidence
                        .append("[Document ")
                        .append(doc)
                        .append("]\n")
                        .append(hit.getPayloadOrThrow("text").getStringValue())
                        .append("\n");
                  }
                  if (citations.isEmpty()) return null;
                  UUID streamId = UUID.randomUUID();
                  AtomicInteger sequence = new AtomicInteger();
                  emit(
                      e,
                      p,
                      Map.of(
                          "type",
                          "AI_START",
                          "messageId",
                          e.messageId(),
                          "streamId",
                          streamId,
                          "citations",
                          citations));
                  ai.stream(
                      e.content(),
                      evidence.toString(),
                      delta -> {
                        rbac.require(e.channelId());
                        for (UUID doc : citations) docs.accessible(doc);
                        emit(
                            e,
                            p,
                            Map.of(
                                "type",
                                "AI_DELTA",
                                "messageId",
                                e.messageId(),
                                "streamId",
                                streamId,
                                "sequence",
                                sequence.getAndIncrement(),
                                "delta",
                                delta));
                      });
                  emit(
                      e,
                      p,
                      Map.of(
                          "type",
                          "AI_DONE",
                          "messageId",
                          e.messageId(),
                          "streamId",
                          streamId,
                          "sequence",
                          sequence.get()));
                  return null;
                } catch (org.springframework.security.access.AccessDeniedException denied) {
                  return null;
                } catch (Exception ex) {
                  if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                  throw new IllegalStateException("Policy suggestion failed", ex);
                }
              });
        });
  }

  private void emit(ChatMessageEvent e, TenantPrincipal p, Object body) {
    try {
      var f =
          new RedisCrossRegionFanoutListener.Fanout(
              p.orgId(), e.channelId(), p.userId(), json.writeValueAsString(body));
      redis.publish(f);
      backbone.fanout(f, p.homeRegion());
    } catch (Exception ex) {
      throw new IllegalStateException("AI delivery failed", ex);
    }
  }
}
