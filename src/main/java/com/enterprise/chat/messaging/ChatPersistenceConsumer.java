package com.enterprise.chat.messaging;

import com.enterprise.chat.config.KafkaTopicConfig;
import com.enterprise.chat.domain.dto.ChatMessageEvent;
import com.enterprise.chat.domain.nosql.*;
import com.enterprise.chat.repository.ScyllaChatRepository;
import com.enterprise.chat.security.*;
import com.enterprise.chat.ws.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.cassandra.core.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ChatPersistenceConsumer {
  private final CassandraTemplate cassandra;
  private final ScyllaChatRepository history;
  private final ComplianceArchiverConsumer.OrganizationKeys organizations;
  private final KmsEnvelopeEncryptionService crypto;
  private final ChatKafkaProducer kafka;
  private final ObjectMapper json;
  private final RedisCrossRegionFanoutListener redis;
  private final HomeRegionBackboneRouter backbone;
  private final String region;

  public ChatPersistenceConsumer(
      CassandraTemplate cassandra,
      ScyllaChatRepository history,
      ComplianceArchiverConsumer.OrganizationKeys organizations,
      KmsEnvelopeEncryptionService crypto,
      ChatKafkaProducer kafka,
      ObjectMapper json,
      RedisCrossRegionFanoutListener redis,
      HomeRegionBackboneRouter backbone,
      @Value("${app.region}") String region) {
    this.cassandra = cassandra;
    this.history = history;
    this.organizations = organizations;
    this.crypto = crypto;
    this.kafka = kafka;
    this.json = json;
    this.redis = redis;
    this.backbone = backbone;
    this.region = region;
  }

  @KafkaListener(topics = KafkaTopicConfig.INBOUND, groupId = "chat-persistence")
  public void persist(String payload) throws Exception {
    var e = json.readValue(payload, ChatMessageEvent.class);
    TenantContext.withOrg(
        e.orgId(),
        () -> {
          var p = organizations.current();
          if (!region.equals(p.getHomeRegion())
              || !region.equals(e.homeRegion())
              || e.messageId().version() != 1)
            throw new SecurityException("Wrong cell or message key");
          String month =
              e.createdAt().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMM"));
          String sealed =
              crypto.encrypt(
                  p.getId(),
                  p.getKmsKeyArn(),
                  "message:" + e.channelId() + ":" + e.messageId(),
                  e.content());
          var row =
              new ChatMessageTable(
                  new ChatMessageKey(e.orgId(), e.channelId(), month, e.messageId()),
                  e.senderId(),
                  e.createdAt(),
                  sealed,
                  e.documentId());
          cassandra.insert(row, InsertOptions.builder().ifNotExists(true).build());
          // Stable IDs and LWT keep replay from changing the authoritative hot record.
          var stored = history.findById(row.key()).orElseThrow();
          var canonical =
              new ChatMessageEvent(
                  e.orgId(),
                  e.channelId(),
                  e.messageId(),
                  stored.senderId(),
                  region,
                  stored.createdAt(),
                  crypto.decrypt(
                      e.orgId(),
                      p.getKmsKeyArn(),
                      "message:" + e.channelId() + ":" + e.messageId(),
                      stored.encryptedContent()),
                  stored.documentId());
          kafka.send(KafkaTopicConfig.AUDIT, e.partitionKey(), canonical);
          kafka.send(KafkaTopicConfig.PERSISTED, e.partitionKey(), canonical);
          kafka.send(KafkaTopicConfig.AI, e.partitionKey(), canonical);
          return null;
        });
  }

  @KafkaListener(topics = KafkaTopicConfig.PERSISTED, groupId = "regional-chat-fanout")
  public void fanout(String payload) throws Exception {
    var e = json.readValue(payload, ChatMessageEvent.class);
    if (!region.equals(e.homeRegion())) throw new SecurityException("Wrong fanout cell");
    var f = new RedisCrossRegionFanoutListener.Fanout(e.orgId(), e.channelId(), null, payload);
    redis.publish(f);
    backbone.fanout(f, e.homeRegion());
  }
}
