package com.enterprise.chat.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "documents")
public class DocumentEntity {
  @Id private java.util.UUID id;

  @Column(name = "org_id")
  private java.util.UUID orgId;

  @Column(name = "channel_id")
  private java.util.UUID channelId;

  @Column(name = "uploader_id")
  private java.util.UUID uploaderId;

  @Column(name = "object_key")
  private String objectKey;

  @Column(name = "object_version")
  private String objectVersion;

  @Column(name = "content_type")
  private String contentType;

  private String status;

  @Column(name = "size_bytes")
  private long sizeBytes;

  @Column(name = "min_role_rank")
  private int minRoleRank;

  @Column(name = "access_expires_at")
  private java.time.Instant accessExpiresAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "document_departments",
      joinColumns = {
        @JoinColumn(name = "document_id", referencedColumnName = "id"),
        @JoinColumn(name = "org_id", referencedColumnName = "org_id")
      })
  @Column(name = "dept_id")
  private java.util.Set<java.util.UUID> allowedDepartmentIds = new java.util.HashSet<>();

  public DocumentEntity() {}

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

  public java.util.UUID getChannelId() {
    return channelId;
  }

  public void setChannelId(java.util.UUID value) {
    this.channelId = value;
  }

  public java.util.UUID getUploaderId() {
    return uploaderId;
  }

  public void setUploaderId(java.util.UUID value) {
    this.uploaderId = value;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public void setObjectKey(String value) {
    this.objectKey = value;
  }

  public String getObjectVersion() {
    return objectVersion;
  }

  public void setObjectVersion(String value) {
    this.objectVersion = value;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String value) {
    this.contentType = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long value) {
    this.sizeBytes = value;
  }

  public int getMinRoleRank() {
    return minRoleRank;
  }

  public void setMinRoleRank(int value) {
    this.minRoleRank = value;
  }

  public java.time.Instant getAccessExpiresAt() {
    return accessExpiresAt;
  }

  public void setAccessExpiresAt(java.time.Instant value) {
    this.accessExpiresAt = value;
  }

  public java.util.Set<java.util.UUID> getAllowedDepartmentIds() {
    return allowedDepartmentIds;
  }

  public void setAllowedDepartmentIds(java.util.Set<java.util.UUID> value) {
    this.allowedDepartmentIds = value;
  }
}
