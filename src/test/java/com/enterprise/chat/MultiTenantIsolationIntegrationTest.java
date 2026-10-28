package com.enterprise.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.enterprise.chat.ai.AiPolicySuggestionWorker;
import com.enterprise.chat.config.PostgresRlsAspect;
import com.enterprise.chat.domain.entity.*;
import com.enterprise.chat.repository.*;
import com.enterprise.chat.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

@DataJpaTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.datasource.hikari.maximum-pool-size=1"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  PostgresRlsAspect.class,
  ChannelRbacValidator.class,
  MultiTenantIsolationIntegrationTest.Aop.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class MultiTenantIsolationIntegrationTest {
  @org.springframework.boot.test.context.TestConfiguration
  @EnableAspectJAutoProxy
  @EnableTransactionManagement(order = 0)
  static class Aop {}

  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4");
  static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001"),
      B = UUID.fromString("00000000-0000-0000-0000-000000000002"),
      UA = UUID.fromString("10000000-0000-0000-0000-000000000001"),
      UB = UUID.fromString("10000000-0000-0000-0000-000000000002"),
      DA = UUID.fromString("20000000-0000-0000-0000-000000000001"),
      DBID = UUID.fromString("20000000-0000-0000-0000-000000000002"),
      CA = UUID.fromString("30000000-0000-0000-0000-000000000001"),
      CB = UUID.fromString("30000000-0000-0000-0000-000000000002");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry props) throws Exception {
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .load()
        .migrate();
    try (var connection =
            DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var s = connection.createStatement()) {
      s.execute("CREATE ROLE isolated_app LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS");
      s.execute("GRANT USAGE ON SCHEMA public TO isolated_app");
      s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO isolated_app");
      for (int i = 1; i <= 2; i++) {
        UUID org = i == 1 ? A : B,
            user = i == 1 ? UA : UB,
            dept = i == 1 ? DA : DBID,
            channel = i == 1 ? CA : CB;
        s.execute(
            "INSERT INTO organizations VALUES ('"
                + org
                + "','Org','eu-central-1','arn:aws:kms:eu-central-1:123456789012:key/"
                + org
                + "')");
        s.execute("INSERT INTO departments VALUES ('" + dept + "','" + org + "','Department')");
        s.execute(
            "INSERT INTO app_users VALUES ('" + user + "','" + org + "','User','MEMBER',true)");
        s.execute(
            "INSERT INTO user_departments VALUES ('" + org + "','" + user + "','" + dept + "')");
        s.execute("INSERT INTO channels VALUES ('" + channel + "','" + org + "','Channel',10)");
        s.execute(
            "INSERT INTO channel_departments VALUES ('"
                + org
                + "','"
                + channel
                + "','"
                + dept
                + "')");
      }
    }
    props.add("spring.datasource.url", DB::getJdbcUrl);
    props.add("spring.datasource.username", () -> "isolated_app");
    props.add("spring.datasource.password", () -> "test-only");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ChannelRepository channels;
  @Autowired DocumentRepository documents;
  @Autowired ChannelRbacValidator rbac;
  @Autowired org.springframework.transaction.PlatformTransactionManager tm;

  private <T> T sqlScope(UUID org, java.util.function.Supplier<T> work) {
    return new TransactionTemplate(tm)
        .execute(
            status -> {
              jdbc.queryForObject(
                  "select set_config('app.org_id',?,true)", String.class, org.toString());
              return work.get();
            });
  }

  @Test
  void runtimeRoleCannotBypassRls() {
    assertThat(
            jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname=current_user",
                Boolean.class))
        .isFalse();
    assertThat(jdbc.queryForObject("select count(*) from organizations", Long.class)).isZero();
    assertThat(sqlScope(A, () -> jdbc.queryForList("select id from organizations", UUID.class)))
        .containsExactly(A);
    assertThat(sqlScope(B, () -> jdbc.queryForList("select id from organizations", UUID.class)))
        .containsExactly(B);
  }

  @Test
  void pooledConnectionDoesNotLeakTenantAfterCommitOrRollback() {
    sqlScope(A, () -> jdbc.queryForObject("select count(*) from channels", Long.class));
    assertThat(jdbc.queryForObject("select count(*) from channels", Long.class)).isZero();
    assertThatThrownBy(
            () ->
                sqlScope(
                    B,
                    () -> {
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from channels", Long.class)).isZero();
  }

  @Test
  void crossTenantWritesAndCompositeForeignKeysAreRejected() {
    assertThatThrownBy(
            () ->
                sqlScope(
                    A,
                    () ->
                        jdbc.update(
                            "insert into departments values (?,?,?)",
                            UUID.randomUUID(),
                            B,
                            "blocked")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                sqlScope(
                    A,
                    () ->
                        jdbc.update("insert into channel_departments values (?,?,?)", A, CA, DBID)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void aspectAndJpaCollectionsEnforceTenantAndDepartmentMembership() {
    TenantPrincipal a = TenantContext.withOrg(A, () -> rbac.loadPrincipal(UA));
    assertThat(a.departmentIds()).containsExactly(DA);
    TenantContext.with(
        a,
        () -> {
          assertThat(rbac.require(CA).getAllowedDepartmentIds()).containsExactly(DA);
          assertThatThrownBy(() -> rbac.require(CB))
              .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
          return null;
        });
    assertThatThrownBy(() -> TenantContext.withOrg(A, () -> rbac.loadPrincipal(UB)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> rbac.loadPrincipal(UA)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void expiredDocumentsNeverPassTheRepositoryAccessGate() {
    UUID id = UUID.randomUUID();
    sqlScope(
        A,
        () ->
            jdbc.update(
                "insert into"
                    + " documents(id,org_id,channel_id,uploader_id,object_key,content_type,size_bytes,min_role_rank,access_expires_at,status)"
                    + " values (?,?,?,?,?,'text/plain',10,10,now()-interval '1 second','READY')",
                id,
                A,
                CA,
                UA,
                A + "/" + id));
    assertThat(sqlScope(A, () -> documents.findAccessible(id, A, Instant.now()))).isEmpty();
  }

  @Test
  void contextRestoresNestedScopesAndClearsAfterFailure() {
    TenantContext.withOrg(
        A,
        () -> {
          assertThat(TenantContext.orgId()).isEqualTo(A);
          assertThatThrownBy(
                  () ->
                      TenantContext.withOrg(
                          B,
                          () -> {
                            throw new IllegalStateException();
                          }))
              .isInstanceOf(IllegalStateException.class);
          assertThat(TenantContext.orgId()).isEqualTo(A);
          return null;
        });
    assertThatThrownBy(TenantContext::orgId).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void vectorFilterAlwaysContainsTenantDepartmentRoleExpiryAndStatus() {
    var p = new TenantPrincipal(A, UA, Set.of(DA), UserRole.MEMBER, "eu-central-1", "key");
    var filter = AiPolicySuggestionWorker.permissionFilter(p, Instant.ofEpochSecond(1234));
    assertThat(filter.getMustList()).hasSize(5);
    var conditions =
        filter.getMustList().stream()
            .collect(
                java.util.stream.Collectors.toMap(c -> c.getField().getKey(), c -> c.getField()));
    assertThat(conditions.get("org_id").getMatch().getKeyword()).isEqualTo(A.toString());
    assertThat(conditions.get("status").getMatch().getKeyword()).isEqualTo("READY");
    assertThat(conditions.get("allowed_dept_ids").getMatch().getKeywords().getStringsList())
        .containsExactly(DA.toString());
    assertThat(conditions.get("min_role_rank").getRange().getLte()).isEqualTo(10);
    assertThat(conditions.get("access_expires_at_epoch").getRange().getGt()).isEqualTo(1234);
  }

  @Test
  void envelopesBindTenantKeyAndMessageAndUseFreshNonces() {
    KmsClient kms = mock(KmsClient.class);
    byte[] dek = new byte[32];
    new java.security.SecureRandom().nextBytes(dek);
    when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
        .thenReturn(
            GenerateDataKeyResponse.builder()
                .plaintext(SdkBytes.fromByteArray(dek))
                .ciphertextBlob(SdkBytes.fromUtf8String("wrapped"))
                .build());
    when(kms.decrypt(any(DecryptRequest.class)))
        .thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(dek)).build());
    var crypto = new KmsEnvelopeEncryptionService(kms, new ObjectMapper());
    String one = crypto.encrypt(A, "key-a", "message:1", "private"),
        two = crypto.encrypt(A, "key-a", "message:1", "private");
    assertThat(one).isNotEqualTo(two);
    assertThat(crypto.decrypt(A, "key-a", "message:1", one)).isEqualTo("private");
    assertThatThrownBy(() -> crypto.decrypt(B, "key-a", "message:1", one))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> crypto.decrypt(A, "key-b", "message:1", one))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> crypto.decrypt(A, "key-a", "message:2", one))
        .isInstanceOf(IllegalStateException.class);
    verify(kms, times(1)).generateDataKey(any(GenerateDataKeyRequest.class));
  }

  @Test
  void archiveUsesFiveCalendarYearsAndRejectsUnprotectedReplay() throws Exception {
    var s3 = mock(software.amazon.awssdk.services.s3.S3Client.class);
    var crypto = mock(KmsEnvelopeEncryptionService.class);
    var keys =
        mock(com.enterprise.chat.messaging.ComplianceArchiverConsumer.OrganizationKeys.class);
    var organization = new OrganizationEntity();
    organization.setId(A);
    organization.setHomeRegion("eu-central-1");
    organization.setKmsKeyArn("key-a");
    when(keys.current()).thenReturn(organization);
    when(crypto.encrypt(any(), any(), any(), any())).thenReturn("{\"version\":1}");
    var mapper = new ObjectMapper().findAndRegisterModules();
    var archiver =
        new com.enterprise.chat.messaging.ComplianceArchiverConsumer(
            mapper, s3, crypto, keys, "archive", "eu-central-1");
    var now = Instant.now();
    var event =
        new com.enterprise.chat.domain.dto.ChatMessageEvent(
            A, CA, UUID.randomUUID(), UA, "eu-central-1", now, "confidential", null);
    String payload = mapper.writeValueAsString(event);
    archiver.archive(payload);
    var request =
        org.mockito.ArgumentCaptor.forClass(
            software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
    verify(s3)
        .putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
    var put = request.getValue();
    assertThat(put.ifNoneMatch()).isEqualTo("*");
    assertThat(put.key()).startsWith(A + "/").endsWith(event.messageId() + ".jsonl");
    assertThat(put.objectLockMode())
        .isEqualTo(software.amazon.awssdk.services.s3.model.ObjectLockMode.COMPLIANCE);
    assertThat(put.objectLockRetainUntilDate())
        .isEqualTo(now.atZone(java.time.ZoneOffset.UTC).plusYears(5).toInstant());
    assertThat(put.ssekmsKeyId()).isEqualTo("key-a");
    when(s3.putObject(
            any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
            any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(
            software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(412).build());
    when(s3.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                .versionId("locked-version")
                .ssekmsKeyId("key-a")
                .build());
    when(s3.getObjectRetention(
            any(software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse.builder()
                .retention(
                    software.amazon.awssdk.services.s3.model.ObjectLockRetention.builder()
                        .mode(
                            software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
                                .COMPLIANCE)
                        .retainUntilDate(put.objectLockRetainUntilDate())
                        .build())
                .build());
    archiver.archive(payload);
    when(s3.getObjectRetention(
            any(software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse.builder()
                .retention(
                    software.amazon.awssdk.services.s3.model.ObjectLockRetention.builder()
                        .mode(
                            software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
                                .GOVERNANCE)
                        .retainUntilDate(put.objectLockRetainUntilDate())
                        .build())
                .build());
    assertThatThrownBy(() -> archiver.archive(payload)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void redisSubscriptionsAreReferenceCountedPerTenantAndChannel() {
    var registry =
        new com.enterprise.chat.ws.LocalWebSocketSessionRegistry(mock(ChannelRbacValidator.class));
    var p = new TenantPrincipal(A, UA, Set.of(DA), UserRole.MEMBER, "eu-central-1", "key");
    var one = mock(org.springframework.web.socket.WebSocketSession.class);
    var two = mock(org.springframework.web.socket.WebSocketSession.class);
    when(one.getId()).thenReturn("one");
    when(two.getId()).thenReturn("two");
    registry.register(one, p, Instant.now().plusSeconds(300));
    registry.register(two, p, Instant.now().plusSeconds(300));
    assertThat(registry.subscribe("one", A, CA)).isTrue();
    assertThat(registry.subscribe("one", A, CA)).isFalse();
    assertThat(registry.subscribe("two", A, CA)).isFalse();
    assertThat(registry.unsubscribe("one", A, CA)).isFalse();
    assertThat(registry.unsubscribe("two", A, CA)).isTrue();
    assertThat(registry.unsubscribe("two", A, CA)).isFalse();
    assertThatThrownBy(() -> registry.subscribe("one", B, CB))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
}
