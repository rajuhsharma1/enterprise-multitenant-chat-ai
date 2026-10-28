package com.enterprise.chat.ai;

import com.enterprise.chat.config.KafkaTopicConfig;
import com.enterprise.chat.domain.dto.DocumentIngestionEvent;
import com.enterprise.chat.security.*;
import com.enterprise.chat.service.TimedDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.ShardKey;
import io.qdrant.client.grpc.JsonWithInt.*;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.*;
import io.qdrant.client.grpc.Points.Vector;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Component
public class DocumentIngestionWorker {
  private final TimedDocumentService docs;
  private final ChannelRbacValidator rbac;
  private final S3Client s3;
  private final QdrantClient vectors;
  private final EmbeddingAndLlmClient ai;
  private final ObjectMapper json;
  private final String bucket, collection, region;

  public DocumentIngestionWorker(
      TimedDocumentService docs,
      ChannelRbacValidator rbac,
      S3Client s3,
      QdrantClient vectors,
      EmbeddingAndLlmClient ai,
      ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${app.s3.documents}") String bucket,
      @org.springframework.beans.factory.annotation.Value("${app.qdrant.collection:policies}")
          String collection,
      @org.springframework.beans.factory.annotation.Value("${app.region}") String region) {
    this.docs = docs;
    this.rbac = rbac;
    this.s3 = s3;
    this.vectors = vectors;
    this.ai = ai;
    this.json = json;
    this.bucket = bucket;
    this.collection = collection;
    this.region = region;
  }

  public static ShardKeySelector tenantShard(UUID org) {
    return ShardKeySelector.newBuilder()
        .addShardKeys(ShardKey.newBuilder().setKeyword(org.toString()))
        .build();
  }

  public static Value text(String value) {
    return Value.newBuilder().setStringValue(value).build();
  }

  @KafkaListener(topics = KafkaTopicConfig.DOCS, groupId = "document-ingestion")
  public void ingest(String payload) throws Exception {
    var event = json.readValue(payload, DocumentIngestionEvent.class);
    TenantContext.withOrg(
        event.orgId(),
        () -> {
          var d = docs.ingestionDocument(event.documentId());
          var p = rbac.loadPrincipal(d.getUploaderId());
          if (!region.equals(p.homeRegion()) || !d.getChannelId().equals(event.channelId()))
            throw new SecurityException("Wrong ingestion cell/channel");
          if (!d.getAccessExpiresAt().isAfter(Instant.now())
              || !Set.of("QUEUED", "READY").contains(d.getStatus())) return null;
          if (!Set.of("application/pdf", "text/plain").contains(d.getContentType())) {
            docs.markReady(d.getId());
            return null;
          }
          try {
            byte[] bytes =
                s3.getObjectAsBytes(
                        GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(d.getObjectKey())
                            .versionId(d.getObjectVersion())
                            .build())
                    .asByteArray();
            if (bytes.length > 52428800 || bytes.length != d.getSizeBytes())
              throw new IllegalArgumentException("Document size mismatch");
            String content;
            if (d.getContentType().equals("application/pdf")) {
              try (var pdf = Loader.loadPDF(bytes)) {
                var stripper = new PDFTextStripper();
                stripper.setEndPage(Math.min(1000, pdf.getNumberOfPages()));
                content = stripper.getText(pdf);
              }
            } else content = new String(bytes, StandardCharsets.UTF_8);
            if (content.length() > 2_000_000)
              throw new IllegalArgumentException("Document text exceeds ingestion limit");
            for (int start = 0, index = 0; start < content.length(); start += 1000, index++) {
              if (!d.getAccessExpiresAt().isAfter(Instant.now())) return null;
              String chunk = content.substring(start, Math.min(start + 1200, content.length()));
              ListValue.Builder depts = ListValue.newBuilder();
              d.getAllowedDepartmentIds().forEach(id -> depts.addValues(text(id.toString())));
              Map<String, Value> metadata = new HashMap<>();
              metadata.put("org_id", text(d.getOrgId().toString()));
              metadata.put("document_id", text(d.getId().toString()));
              metadata.put("allowed_dept_ids", Value.newBuilder().setListValue(depts).build());
              metadata.put(
                  "min_role_rank", Value.newBuilder().setIntegerValue(d.getMinRoleRank()).build());
              metadata.put(
                  "access_expires_at_epoch",
                  Value.newBuilder()
                      .setIntegerValue(d.getAccessExpiresAt().getEpochSecond())
                      .build());
              metadata.put("status", text("READY"));
              metadata.put("text", text(chunk));
              UUID id =
                  UUID.nameUUIDFromBytes(
                      (d.getId() + ":" + d.getObjectVersion() + ":" + index)
                          .getBytes(StandardCharsets.UTF_8));
              var point =
                  PointStruct.newBuilder()
                      .setId(PointId.newBuilder().setUuid(id.toString()))
                      .setVectors(
                          Vectors.newBuilder()
                              .setVector(Vector.newBuilder().addAllData(ai.embed(chunk))))
                      .putAllPayload(metadata)
                      .build();
              vectors
                  .upsertAsync(
                      UpsertPoints.newBuilder()
                          .setCollectionName(collection)
                          .setWait(true)
                          .setShardKeySelector(tenantShard(d.getOrgId()))
                          .addPoints(point)
                          .build())
                  .get(30, TimeUnit.SECONDS);
            }
            docs.markReady(d.getId());
            return null;
          } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Document ingestion failed", e);
          }
        });
  }
}
