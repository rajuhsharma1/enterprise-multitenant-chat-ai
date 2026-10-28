package com.enterprise.chat.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "channels")
public class ChannelEntity {
  @Id private java.util.UUID id;

  @Column(name = "org_id")
  private java.util.UUID orgId;

  private String name;

  @Column(name = "min_role_rank")
  private int minRoleRank;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "channel_departments",
      joinColumns = {
        @JoinColumn(name = "channel_id", referencedColumnName = "id"),
        @JoinColumn(name = "org_id", referencedColumnName = "org_id")
      })
  @Column(name = "dept_id")
  private java.util.Set<java.util.UUID> allowedDepartmentIds = new java.util.HashSet<>();

  public ChannelEntity() {}

  public java.util.UUID getId() {
    return id;
  }

  public void setId(java.util.UUID value) {
    this.id = value;
  }

  public java.util.UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(java.util.UUID value) {
    this.orgId = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public int getMinRoleRank() {
    return minRoleRank;
  }

  public void setMinRoleRank(int value) {
    this.minRoleRank = value;
  }

  public java.util.Set<java.util.UUID> getAllowedDepartmentIds() {
    return allowedDepartmentIds;
  }

  public void setAllowedDepartmentIds(java.util.Set<java.util.UUID> value) {
    this.allowedDepartmentIds = value;
  }
}
