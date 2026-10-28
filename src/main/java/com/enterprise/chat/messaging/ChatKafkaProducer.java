package com.enterprise.chat.messaging;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.enterprise.chat.config.KafkaTopicConfig;
import com.enterprise.chat.domain.dto.*;
import com.enterprise.chat.security.*;
import com.enterprise.chat.service.TimedDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatKafkaProducer {
  private final KafkaTemplate<String, String> kafka;
  private final ChannelRbacValidator rbac;
  private final TimedDocumentService docs;
  private final StringRedisTemplate redis;
  private final KmsEnvelopeEncryptionService crypto;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final String region;

  public ChatKafkaProducer(
      KafkaTemplate<String, String> kafka,
      ChannelRbacValidator rbac,
      TimedDocumentService docs,
      StringRedisTemplate redis,
      KmsEnvelopeEncryptionService crypto,
      ObjectMapper json,
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager tm,
      @Value("${app.region}") String region) {
    this.kafka = kafka;
    this.rbac = rbac;
    this.docs = docs;
    this.redis = redis;
    this.crypto = crypto;
    this.json = json;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(tm);
    this.region = region;
  }

  public ChatMessageEvent accept(InboundWsFrame frame) {
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    rbac.require(frame.channelId());
    if (!region.equals(p.homeRegion())
        || frame.type() != InboundWsFrame.Type.MESSAGE
        || frame.requestId() == null
        || frame.content() == null
        || frame.content().isBlank()
        || frame.content().length() > 16000)
      throw new IllegalArgumentException("Invalid home-region message");
    if (frame.documentId() != null
        && !docs.accessible(frame.documentId()).getChannelId().equals(frame.channelId()))
      throw ChannelRbacValidator.denied();
    try {
      var candidate =
          new ChatMessageEvent(
              p.orgId(),
              frame.channelId(),
              Uuids.timeBased(),
              p.userId(),
              p.homeRegion(),
              Instant.now(),
              frame.content(),
              frame.documentId());
      String dedup = "chat:request:" + p.orgId() + ":" + p.userId() + ":" + frame.requestId();
      String purpose = "request:" + p.userId() + ":" + frame.requestId();
      String encoded =
          crypto.encrypt(p.orgId(), p.kmsKeyArn(), purpose, json.writeValueAsString(candidate));
      boolean inserted =
          Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(dedup, encoded, Duration.ofDays(1)));
      String selected = inserted ? encoded : redis.opsForValue().get(dedup);
      if (selected == null)
        throw new IllegalStateException("Idempotency entry expired during submit");
      ChatMessageEvent event =
          json.readValue(
              crypto.decrypt(p.orgId(), p.kmsKeyArn(), purpose, selected), ChatMessageEvent.class);
      if (!event.channelId().equals(frame.channelId())
          || !event.content().equals(frame.content())
          || !Objects.equals(event.documentId(), frame.documentId()))
        throw new IllegalArgumentException("Request ID already used with different content");
      send(KafkaTopicConfig.INBOUND, event.partitionKey(), event);
      return event;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public void send(String topic, String key, Object event) {
    try {
      kafka.send(topic, key, json.writeValueAsString(event)).get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka acknowledgement failed", e);
    }
  }

  @Scheduled(fixedDelayString = "${app.outbox.poll-ms:1000}")
  public void dispatchDocumentOutbox() {
    transactions.executeWithoutResult(
        status -> {
          var rows =
              jdbc.queryForList(
                  "select id,org_id,channel_id,payload::text as body from ingestion_outbox order by"
                      + " created_at for update skip locked limit 50");
          for (var row : rows) {
            try {
              kafka
                  .send(
                      KafkaTopicConfig.DOCS,
                      row.get("org_id") + ":" + row.get("channel_id"),
                      row.get("body").toString())
                  .get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
              if (e instanceof InterruptedException) Thread.currentThread().interrupt();
              throw new IllegalStateException("Outbox delivery failed", e);
            }
            jdbc.update("delete from ingestion_outbox where id=?", row.get("id"));
          }
        });
  }
}
