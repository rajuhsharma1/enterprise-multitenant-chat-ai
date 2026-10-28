package com.enterprise.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaTopicConfig {
  public static final String INBOUND = "chat.messages.inbound",
      PERSISTED = "chat.messages.persisted",
      AI = "ai.suggestions.request",
      DOCS = "docs.ingestion.jobs",
      AUDIT = "compliance.audit.stream";

  @Bean
  KafkaAdmin.NewTopics topics(
      @Value("${app.kafka.partitions:48}") int partitions,
      @Value("${app.kafka.replicas:3}") int replicas) {
    return new KafkaAdmin.NewTopics(
        java.util.stream.Stream.of(INBOUND, PERSISTED, AI, DOCS, AUDIT)
            .map(
                n ->
                    TopicBuilder.name(n)
                        .partitions(partitions)
                        .replicas(replicas)
                        .config("min.insync.replicas", replicas > 1 ? "2" : "1")
                        .config("retention.ms", "1209600000")
                        .build())
            .toArray(NewTopic[]::new));
  }

  @Bean
  DefaultErrorHandler kafkaErrorHandler() {
    // Never silently skip a failed compliance/persistence record; alert on blocked partitions.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new FixedBackOff(2000, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setClassifications(java.util.Map.of(), true);
    handler.setResetStateOnRecoveryFailure(true);
    return handler;
  }
}
