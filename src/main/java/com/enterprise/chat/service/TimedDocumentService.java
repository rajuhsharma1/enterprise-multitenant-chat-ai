package com.enterprise.chat.service;

import com.enterprise.chat.domain.dto.*;
import com.enterprise.chat.domain.entity.*;
import com.enterprise.chat.repository.*;
import com.enterprise.chat.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Service
public class TimedDocumentService {
  private final DocumentRepository docs;
  private final ChannelRbacValidator rbac;
  private final S3Client s3;
  private final S3Presigner signer;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final String bucket, region;

  public TimedDocumentService(
      DocumentRepository docs,
      ChannelRbacValidator rbac,
      S3Client s3,
      S3Presigner signer,
      JdbcTemplate jdbc,
      ObjectMapper json,
      @Value("${app.s3.documents}") String bucket,
      @Value("${app.region}") String region) {
    this.docs = docs;
    this.rbac = rbac;
    this.s3 = s3;
    this.signer = signer;
    this.jdbc = jdbc;
    this.json = json;
    this.bucket = bucket;
    this.region = region;
  }

  private void home() {
    if (!region.equals(TenantContext.principal().homeRegion()))
      throw new IllegalArgumentException("Use the organization's home region for documents");
  }

  @Transactional
  public PresignedUrlResponse upload(PresignedUploadRequest request) {
    home();
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    var channel = rbac.require(request.channelId());
    if (!Set.of(10, 20, 30, 40).contains(request.minRoleRank()))
      throw new IllegalArgumentException("Invalid role rank");
    if (request.contentType() == null
        || request.accessExpiresAt().isBefore(Instant.now().plusSeconds(60))
        || request.accessExpiresAt().isAfter(Instant.now().plus(Duration.ofDays(365))))
      throw new IllegalArgumentException("Invalid upload lifetime");
    if (!p.departmentIds().containsAll(request.allowedDepartmentIds())
        || !channel.getAllowedDepartmentIds().containsAll(request.allowedDepartmentIds())
        || request.minRoleRank() < channel.getMinRoleRank()
        || request.minRoleRank() > p.role().rank()) throw ChannelRbacValidator.denied();
    var d = new DocumentEntity();
    d.setId(UUID.randomUUID());
    d.setOrgId(p.orgId());
    d.setChannelId(request.channelId());
    d.setUploaderId(p.userId());
    d.setObjectKey(p.orgId() + "/" + d.getId());
    d.setContentType(request.contentType());
    d.setStatus("UPLOADING");
    d.setSizeBytes(request.sizeBytes());
    d.setMinRoleRank(request.minRoleRank());
    d.setAccessExpiresAt(request.accessExpiresAt());
    d.setAllowedDepartmentIds(new HashSet<>(request.allowedDepartmentIds()));
    docs.save(d);
    Duration ttl =
        Duration.ofSeconds(
            Math.min(300, Duration.between(Instant.now(), d.getAccessExpiresAt()).toSeconds()));
    var put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(d.getObjectKey())
            .contentType(d.getContentType())
            .contentLength(d.getSizeBytes())
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(p.kmsKeyArn())
            .build();
    var url =
        signer.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());
    Map<String, String> headers = new HashMap<>();
    url.signedHeaders()
        .forEach(
            (k, v) -> {
              if (!k.equalsIgnoreCase("host")) headers.put(k, String.join(",", v));
            });
    return new PresignedUrlResponse(
        d.getId(), url.url().toString(), url.expiration(), Map.copyOf(headers));
  }

  @Transactional
  public void complete(UUID id) {
    home();
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    var d = docs.findById(id).orElseThrow(ChannelRbacValidator::denied);
    rbac.require(d.getChannelId());
    if (!d.getUploaderId().equals(p.userId()) || !d.getAccessExpiresAt().isAfter(Instant.now()))
      throw ChannelRbacValidator.denied();
    if (!d.getStatus().equals("UPLOADING")) return;
    var head =
        s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(d.getObjectKey()).build());
    if (head.contentLength() != d.getSizeBytes()
        || !d.getContentType().equals(head.contentType())
        || head.versionId() == null
        || !p.kmsKeyArn().equals(head.ssekmsKeyId()))
      throw new IllegalArgumentException("Upload metadata/version/KMS key mismatch");
    d.setObjectVersion(head.versionId());
    d.setStatus("QUEUED");
    docs.save(d);
    try {
      jdbc.update(
          "insert into ingestion_outbox(id, org_id, channel_id, payload) values (?,?,?,?::jsonb) on"
              + " conflict do nothing",
          d.getId(),
          p.orgId(),
          d.getChannelId(),
          json.writeValueAsString(
              new DocumentIngestionEvent(p.orgId(), d.getChannelId(), d.getId())));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional(readOnly = true)
  public DocumentEntity accessible(UUID id) {
    home();
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    var d =
        docs.findAccessible(id, p.orgId(), Instant.now()).orElseThrow(ChannelRbacValidator::denied);
    rbac.require(d.getChannelId());
    if (p.role().rank() < d.getMinRoleRank()
        || d.getAllowedDepartmentIds().stream().noneMatch(p.departmentIds()::contains))
      throw ChannelRbacValidator.denied();
    return d;
  }

  @Transactional(readOnly = true)
  public PresignedUrlResponse download(UUID id) {
    var d = accessible(id);
    long seconds =
        Math.min(60, Duration.between(Instant.now(), d.getAccessExpiresAt()).toSeconds());
    if (seconds < 1) throw ChannelRbacValidator.denied();
    Duration ttl = Duration.ofSeconds(seconds);
    var url =
        signer.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(d.getObjectKey())
                        .versionId(d.getObjectVersion())
                        .responseContentDisposition("attachment")
                        .build())
                .build());
    if (url.expiration().isAfter(d.getAccessExpiresAt())) throw ChannelRbacValidator.denied();
    return new PresignedUrlResponse(d.getId(), url.url().toString(), url.expiration(), Map.of());
  }

  @Transactional(readOnly = true)
  public DocumentEntity ingestionDocument(UUID id) {
    return docs.findById(id).orElseThrow(ChannelRbacValidator::denied);
  }

  @Transactional
  public void markReady(UUID id) {
    var d = docs.findById(id).orElseThrow(ChannelRbacValidator::denied);
    if (d.getStatus().equals("QUEUED")) {
      d.setStatus("READY");
      docs.save(d);
    }
  }
}
