package com.enterprise.chat.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "departments")
public class DepartmentEntity {
  @Id private java.util.UUID id;

  @Column(name = "org_id")
  private java.util.UUID orgId;

  private String name;

  public DepartmentEntity() {}

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
}
