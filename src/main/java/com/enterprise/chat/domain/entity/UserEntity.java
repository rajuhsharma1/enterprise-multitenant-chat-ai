package com.enterprise.chat.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "app_users")
public class UserEntity {
  @Id private java.util.UUID id;

  @Column(name = "org_id")
  private java.util.UUID orgId;

  @Column(name = "display_name")
  private String displayName;

  @Enumerated(EnumType.STRING)
  private UserRole role;

  private boolean active;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "user_departments",
      joinColumns = {
        @JoinColumn(name = "user_id", referencedColumnName = "id"),
        @JoinColumn(name = "org_id", referencedColumnName = "org_id")
      })
  @Column(name = "dept_id")
  private java.util.Set<java.util.UUID> departmentIds = new java.util.HashSet<>();

  public UserEntity() {}

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

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String value) {
    this.displayName = value;
  }

  public UserRole getRole() {
    return role;
  }

  public void setRole(UserRole value) {
    this.role = value;
  }

  public boolean getActive() {
    return active;
  }

  public void setActive(boolean value) {
    this.active = value;
  }

  public java.util.Set<java.util.UUID> getDepartmentIds() {
    return departmentIds;
  }

  public void setDepartmentIds(java.util.Set<java.util.UUID> value) {
    this.departmentIds = value;
  }
}
