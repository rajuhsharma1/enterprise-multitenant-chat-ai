package com.enterprise.chat.domain.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PresignedUrlResponse(
    UUID documentId, String url, Instant expiresAt, Map<String, String> requiredHeaders) {}
