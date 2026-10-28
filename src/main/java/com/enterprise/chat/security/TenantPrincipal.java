package com.enterprise.chat.security;

import com.enterprise.chat.domain.entity.UserRole;
import java.util.Set;
import java.util.UUID;

public record TenantPrincipal(
    UUID orgId,
    UUID userId,
    Set<UUID> departmentIds,
    UserRole role,
    String homeRegion,
    String kmsKeyArn) {
  public TenantPrincipal {
    departmentIds = Set.copyOf(departmentIds);
  }
}
