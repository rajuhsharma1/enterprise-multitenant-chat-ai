package com.enterprise.chat.domain.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageEvent(
    UUID orgId,
    UUID channelId,
    UUID messageId,
    UUID senderId,
    String homeRegion,
    Instant createdAt,
    String content,
    UUID documentId) {
  public String partitionKey() {
    return orgId + ":" + channelId;
  }
}
