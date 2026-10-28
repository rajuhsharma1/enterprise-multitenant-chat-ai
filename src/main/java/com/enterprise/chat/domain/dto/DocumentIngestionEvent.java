package com.enterprise.chat.domain.dto;

import java.util.UUID;

public record DocumentIngestionEvent(UUID orgId, UUID channelId, UUID documentId) {}
