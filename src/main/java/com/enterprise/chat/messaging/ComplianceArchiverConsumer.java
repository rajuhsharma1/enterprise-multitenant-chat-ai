package com.enterprise.chat.messaging;

import com.enterprise.chat.config.KafkaTopicConfig;
import com.enterprise.chat.domain.dto.ChatMessageEvent;
import com.enterprise.chat.repository.OrganizationRepository;
import com.enterprise.chat.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class ComplianceArchiverConsumer {
  private final ObjectMapper json;
  private final S3Client s3;
  private final KmsEnvelopeEncryptionService crypto;
  private final OrganizationKeys keys;
  private final String bucket, region;

  public ComplianceArchiverConsumer(
      ObjectMapper json,
      S3Client s3,
      KmsEnvelopeEncryptionService crypto,
      OrganizationKeys keys,
      @Value("${app.s3.archive}") String bucket,
      @Value("${app.region}") String region) {
    this.json = json;
    this.s3 = s3;
    this.crypto = crypto;
    this.keys = keys;
    this.bucket = bucket;
    this.region = region;
  }

  @KafkaListener(topics = KafkaTopicConfig.AUDIT, groupId = "compliance-worm-archiver")
  public void archive(String payload) throws Exception {
    var e = json.readValue(payload, ChatMessageEvent.class);
    TenantContext.withOrg(
        e.orgId(),
        () -> {
          var org = keys.current();
          if (!region.equals(e.homeRegion()) || !region.equals(org.getHomeRegion()))
            throw new SecurityException("Wrong archive cell");
          try {
            String encrypted =
                crypto.encrypt(e.orgId(), org.getKmsKeyArn(), "audit:" + e.messageId(), payload);
            String line =
                json.writeValueAsString(
                        java.util.Map.of(
                            "org_id",
                            e.orgId(),
                            "message_id",
                            e.messageId(),
                            "envelope",
                            json.readTree(encrypted)))
                    + "\n";
            Instant retainUntil = e.createdAt().atZone(ZoneOffset.UTC).plusYears(5).toInstant();
            if (retainUntil.isBefore(Instant.now()))
              retainUntil = Instant.now().atZone(ZoneOffset.UTC).plusYears(5).toInstant();
            String key =
                e.orgId()
                    + "/"
                    + e.createdAt()
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    + "/"
                    + e.messageId()
                    + ".jsonl";
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/x-ndjson")
                    .ifNoneMatch("*")
                    .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(org.getKmsKeyArn())
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(retainUntil)
                    .build(),
                RequestBody.fromString(line));
            return null;
          } catch (S3Exception ex) {
            if (ex.statusCode() != 412) throw ex;
            String key =
                e.orgId()
                    + "/"
                    + e.createdAt()
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    + "/"
                    + e.messageId()
                    + ".jsonl";
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            var retention =
                s3.getObjectRetention(
                        GetObjectRetentionRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .versionId(head.versionId())
                            .build())
                    .retention();
            Instant required = e.createdAt().atZone(ZoneOffset.UTC).plusYears(5).toInstant();
            if (retention.mode() != ObjectLockRetentionMode.COMPLIANCE
                || retention.retainUntilDate() == null
                || retention.retainUntilDate().isBefore(required)
                || !org.getKmsKeyArn().equals(head.ssekmsKeyId()))
              throw new IllegalStateException(
                  "Existing archive failed retention/encryption validation");
            return null;
          } catch (Exception ex) {
            throw new IllegalStateException("WORM archival failed", ex);
          }
        });
  }

  @Service
  public static class OrganizationKeys {
    private final OrganizationRepository organizations;

    public OrganizationKeys(OrganizationRepository organizations) {
      this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public com.enterprise.chat.domain.entity.OrganizationEntity current() {
      return organizations
          .findById(TenantContext.orgId())
          .orElseThrow(ChannelRbacValidator::denied);
    }
  }
}
