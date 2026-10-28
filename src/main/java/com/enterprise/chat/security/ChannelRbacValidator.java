package com.enterprise.chat.security;

import com.enterprise.chat.domain.entity.*;
import com.enterprise.chat.repository.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelRbacValidator {
  private final ChannelRepository channels;
  private final UserRepository users;
  private final OrganizationRepository organizations;

  public ChannelRbacValidator(
      ChannelRepository channels, UserRepository users, OrganizationRepository organizations) {
    this.channels = channels;
    this.users = users;
    this.organizations = organizations;
  }

  @Transactional(readOnly = true)
  public TenantPrincipal loadPrincipal(UUID userId) {
    var org = organizations.findById(TenantContext.orgId()).orElseThrow(() -> denied());
    var user =
        users
            .findById(userId)
            .filter(u -> u.getActive() && u.getOrgId().equals(org.getId()))
            .orElseThrow(() -> denied());
    return new TenantPrincipal(
        org.getId(),
        user.getId(),
        user.getDepartmentIds(),
        user.getRole(),
        org.getHomeRegion(),
        org.getKmsKeyArn());
  }

  @Transactional(readOnly = true)
  public ChannelEntity require(UUID channelId) {
    TenantPrincipal p = loadPrincipal(TenantContext.principal().userId());
    var c =
        channels
            .findById(channelId)
            .filter(ch -> ch.getOrgId().equals(p.orgId()))
            .orElseThrow(() -> denied());
    if (p.role().rank() < c.getMinRoleRank()
        || c.getAllowedDepartmentIds().stream().noneMatch(p.departmentIds()::contains))
      throw denied();
    return c;
  }

  public static AccessDeniedException denied() {
    return new AccessDeniedException("Resource unavailable");
  }
}
