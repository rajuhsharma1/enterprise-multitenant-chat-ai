package com.enterprise.chat.domain.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PresignedUploadRequest(
    @NotNull UUID channelId,
    @NotBlank @Size(max = 255) String filename,
    @Pattern(regexp = "application/pdf|text/plain|image/png|image/jpeg|video/mp4")
        String contentType,
    @Min(1) @Max(52428800) long sizeBytes,
    @NotNull @Future Instant accessExpiresAt,
    @NotEmpty Set<UUID> allowedDepartmentIds,
    @Min(10) @Max(40) int minRoleRank) {}
