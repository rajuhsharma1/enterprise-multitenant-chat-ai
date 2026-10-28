package com.enterprise.chat.controller;

import com.enterprise.chat.domain.entity.UserRole;
import com.enterprise.chat.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@RestController
@RequestMapping("/api/admin/compliance")
public class AdminComplianceController {
  public record ArchiveObject(
      String key, String versionId, String retentionMode, java.time.Instant retainUntil) {}

  public record ArchivePage(List<String> keys, String nextToken) {}

  private final ChannelRbacValidator rbac;
  private final S3Client s3;
  private final String bucket, region;

  public AdminComplianceController(
      ChannelRbacValidator rbac,
      S3Client s3,
      @Value("${app.s3.archive}") String bucket,
      @Value("${app.region}") String region) {
    this.rbac = rbac;
    this.s3 = s3;
    this.bucket = bucket;
    this.region = region;
  }

  private TenantPrincipal require() {
    var p = rbac.loadPrincipal(TenantContext.principal().userId());
    if (p.role() != UserRole.COMPLIANCE || !region.equals(p.homeRegion()))
      throw ChannelRbacValidator.denied();
    return p;
  }

  @GetMapping
  public ArchivePage list(
      @RequestParam(defaultValue = "") String day, @RequestParam(required = false) String cursor) {
    var p = require();
    if (!day.isEmpty() && !day.matches("[0-9]{4}/[0-9]{2}/[0-9]{2}"))
      throw new IllegalArgumentException("Invalid day");
    var result =
        s3.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(p.orgId() + "/" + (day.isEmpty() ? "" : day + "/"))
                .maxKeys(100)
                .continuationToken(cursor)
                .build());
    return new ArchivePage(
        result.contents().stream().map(S3Object::key).toList(), result.nextContinuationToken());
  }

  @GetMapping("/retention")
  public ArchiveObject retention(@RequestParam String key) {
    var p = require();
    if (!key.startsWith(p.orgId() + "/") || key.contains("..")) throw ChannelRbacValidator.denied();
    var response =
        s3.getObjectRetention(GetObjectRetentionRequest.builder().bucket(bucket).key(key).build());
    var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    return new ArchiveObject(
        key,
        head.versionId(),
        response.retention().modeAsString(),
        response.retention().retainUntilDate());
  }
}
