package com.enterprise.chat.config;

import io.qdrant.client.*;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class QdrantS3Config {
  @Bean(destroyMethod = "close")
  QdrantClient qdrantClient(
      @Value("${app.qdrant.host}") String host,
      @Value("${app.qdrant.grpc-port:6334}") int port,
      @Value("${app.qdrant.tls:true}") boolean tls,
      @Value("${app.qdrant.api-key:}") String key) {
    var b = QdrantGrpcClient.newBuilder(host, port, tls);
    if (!key.isBlank()) b.withApiKey(key);
    return new QdrantClient(b.build());
  }

  @Bean
  S3Client s3(
      @Value("${app.region}") String region, @Value("${app.aws.endpoint:}") String endpoint) {
    var b = S3Client.builder().region(Region.of(region)).forcePathStyle(!endpoint.isBlank());
    if (!endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
    return b.build();
  }

  @Bean
  S3Presigner presigner(
      @Value("${app.region}") String region, @Value("${app.aws.endpoint:}") String endpoint) {
    var b = S3Presigner.builder().region(Region.of(region));
    if (!endpoint.isBlank())
      b.endpointOverride(URI.create(endpoint))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    return b.build();
  }

  @Bean
  KmsClient kms(
      @Value("${app.region}") String region, @Value("${app.aws.endpoint:}") String endpoint) {
    var b = KmsClient.builder().region(Region.of(region));
    if (!endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
    return b.build();
  }
}
