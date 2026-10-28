package com.enterprise.chat.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "organizations")
public class OrganizationEntity {
  @Id private java.util.UUID id;

  private String name;

  @Column(name = "home_region")
  private String homeRegion;

  @Column(name = "kms_key_arn")
  private String kmsKeyArn;

  public OrganizationEntity() {}

  public java.util.UUID getId() {
    return id;
  }

  public void setId(java.util.UUID value) {
    this.id = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getHomeRegion() {
    return homeRegion;
  }

  public void setHomeRegion(String value) {
    this.homeRegion = value;
  }

  public String getKmsKeyArn() {
    return kmsKeyArn;
  }

  public void setKmsKeyArn(String value) {
    this.kmsKeyArn = value;
  }
}
