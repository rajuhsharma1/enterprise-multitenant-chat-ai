package com.enterprise.chat.domain.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record InboundWsFrame(
    @NotNull Type type,
    @NotNull UUID channelId,
    UUID requestId,
    @Size(max = 16000) String content,
    UUID documentId) {
  public enum Type {
    SUBSCRIBE,
    UNSUBSCRIBE,
    MESSAGE
  }
}
