# Enterprise Multitenant Chat AI

Java 21 / Spring Boot 3.3.4 regional chat and policy retrieval service. Maven produces an executable JAR. The repository contains all 47 Java source files requested, PostgreSQL and CQL schemas, local infrastructure, and a PostgreSQL isolation test suite.

## Build and run

Requirements: a **JDK** 21, Maven 3.9+, Docker Engine with Compose v2, and at least 6 GB available container memory.

```sh
mvn --batch-mode verify
docker compose config --quiet
docker compose up -d
```

`verify` uses Testcontainers PostgreSQL 16 with a NOSUPERUSER/NOBYPASSRLS application login. Docker must be accessible. The local Compose stack runs PostgreSQL, Scylla, Redis, Kafka, Qdrant, and LocalStack; one-shot services initialize the SQL/CQL schemas. It starts infrastructure only. Application startup additionally requires provisioning AWS resources, a Qdrant collection and tenant shard, tenant membership, and a real OIDC issuer as described below.

```sh
export APP_REGION=eu-central-1
export AWS_REGION=eu-central-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_ENDPOINT_URL=http://localhost:4566
export QDRANT_TLS=false
mvn spring-boot:run
```

These AWS credentials and the checked-in database passwords are local development defaults. Use workload identity, secret injection, encrypted network connections, and separate migration/application roles in deployments. `AWS_ENDPOINT_URL` is read by the app for the S3, presigner, and KMS endpoint. URLs returned by the presigner must be reachable by upload clients.

## Local AWS and Qdrant provisioning

Create one KMS key per organization. The following provisions resources for the demonstration tenant `00000000-0000-0000-0000-000000000001`:

```sh
docker compose exec localstack awslocal kms create-key --description chat-demo-org
docker compose exec localstack awslocal kms create-alias --alias-name alias/chat-demo-org --target-key-id "$(docker compose exec -T localstack awslocal kms list-keys --query 'Keys[0].KeyId' --output text)"
docker compose exec localstack awslocal s3api create-bucket --bucket enterprise-chat-documents --region eu-central-1 --create-bucket-configuration LocationConstraint=eu-central-1
docker compose exec localstack awslocal s3api put-bucket-versioning --bucket enterprise-chat-documents --versioning-configuration Status=Enabled
docker compose exec localstack awslocal s3api create-bucket --bucket enterprise-chat-archive --region eu-central-1 --create-bucket-configuration LocationConstraint=eu-central-1 --object-lock-enabled-for-bucket
docker compose exec localstack awslocal s3api put-object-lock-configuration --bucket enterprise-chat-archive --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Years":5}}}'

curl --fail-with-body -X PUT http://localhost:6333/collections/policies -H 'Content-Type: application/json' -d '{"vectors":{"size":1536,"distance":"Cosine"},"sharding_method":"custom"}'
curl --fail-with-body -X PUT http://localhost:6333/collections/policies/shards -H 'Content-Type: application/json' -d '{"shard_key":"00000000-0000-0000-0000-000000000001"}'
for field in org_id allowed_dept_ids status document_id; do
  curl --fail-with-body -X PUT 'http://localhost:6333/collections/policies/index?wait=true' -H 'Content-Type: application/json' -d "{\"field_name\":\"$field\",\"field_schema\":\"keyword\"}"
done
for field in min_role_rank access_expires_at_epoch; do
  curl --fail-with-body -X PUT 'http://localhost:6333/collections/policies/index?wait=true' -H 'Content-Type: application/json' -d "{\"field_name\":\"$field\",\"field_schema\":\"integer\"}"
done
```

For AWS production, provision regional buckets without LocalStack, enable versioning, Object Lock COMPLIANCE default retention, TLS-only bucket policies, SSE-KMS, and restrictive IAM. Writers need `s3:PutObject`, `s3:PutObjectRetention`, `s3:GetObjectRetention`, `s3:GetObject`, and KMS permissions as applicable. Compliance listing needs `s3:ListBucket`. No runtime role should disable retention or change bucket policies. Keep keys available for at least the retained data lifetime. LocalStack exercises API compatibility; AWS retention enforcement requires validation on AWS.

Use the actual **key ARN**, rather than an alias, in the organization row. Retrieve the ARN with `awslocal kms describe-key --key-id alias/chat-demo-org --query KeyMetadata.Arn --output text`. Load organization, department, membership, and channel metadata through a privileged provisioning identity, in one transaction. A complete example, after substituting the key ARN returned by KMS, is:

```sql
BEGIN;
INSERT INTO organizations VALUES ('00000000-0000-0000-0000-000000000001','Demo','eu-central-1','arn:aws:kms:eu-central-1:000000000000:key/REPLACE_WITH_CREATED_KEY_ID');
INSERT INTO departments VALUES ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Engineering');
INSERT INTO app_users VALUES ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Demo user','MEMBER',true);
INSERT INTO user_departments VALUES ('00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001');
INSERT INTO channels VALUES ('30000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','General',10);
INSERT INTO channel_departments VALUES ('00000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001');
COMMIT;
```

Create another custom Qdrant shard for each tenant before enabling ingestion. Embedding dimensions in `AI_DIMENSIONS` and the Qdrant collection must match the selected embedding model. Set `AI_BASE_URL`, `AI_API_KEY`, `AI_EMBEDDING_MODEL`, and `AI_CHAT_MODEL` for an OpenAI-compatible endpoint in the approved region. The default local URL does not install or download models. Sensitive prompts and policy chunks go to this endpoint; select one that meets the organization's residency policy.

## Authentication and API

Set `JWT_ISSUER`, `JWT_JWK_SET_URI`, and `JWT_AUDIENCE` to the organization's OIDC infrastructure. Tokens require a UUID `sub` matching `app_users.id`, UUID `org_id`, and the configured audience. Signature, issuer, timestamps, audience, active membership, departments, and role are validated. Authorization comes from PostgreSQL membership, not arbitrary token role claims. Roles rank MEMBER=10, MANAGER=20, ADMIN=30, COMPLIANCE=40. Compliance APIs explicitly require COMPLIANCE. A channel/document requires both department membership and minimum role; even administrators have no implicit cross-department bypass.

All HTTP APIs use `Authorization: Bearer ...`. WebSocket upgrades also require this header. Native clients and gateways can supply it. Browser JavaScript requires a same-origin authentication gateway that injects the bearer header during the upgrade; do not put tokens in query strings. Configure `WS_ALLOWED_ORIGINS` explicitly.

Connect to `/ws/chat`, then send:

```json
{"type":"SUBSCRIBE","channelId":"30000000-0000-0000-0000-000000000001"}
```

```json
{"type":"MESSAGE","channelId":"30000000-0000-0000-0000-000000000001","requestId":"40000000-0000-0000-0000-000000000001","content":"What is our leave policy?"}
```

Use a fresh UUID request ID per logical send and reuse it for retries for up to 24 hours. ACK means Kafka accepted the inbound event. Persisted messages have a stable timeuuid `messageId`; clients deduplicate replay by that ID. AI frames are `AI_START`, `AI_DELTA`, `AI_DONE`, scoped to the requesting user, with a `streamId` and sequence number. A new stream ID supersedes a partially delivered attempt. Redis provides live delivery; reconnect and fetch history to recover missed chat messages. WebSockets expire with the JWT and must reconnect with a renewed token.

- `GET /api/channels/{channel}/messages?month=202610&limit=50` returns descending timeuuid history for one UTC month. Pass the last `messageId` as `before` to continue; walk earlier months explicitly.
- `POST /api/documents/upload-url` accepts `channelId`, `filename`, `contentType`, `sizeBytes`, `accessExpiresAt`, `allowedDepartmentIds`, and `minRoleRank`.
- Upload the exact declared bytes using PUT and every `requiredHeaders` entry. Upload URLs last at most five minutes. Versioning and per-org SSE-KMS are mandatory.
- `POST /api/documents/{id}/complete` checks object metadata and captures its immutable version ID; the version is used for ingestion and downloads, so replaying an upload URL cannot change a completed document.
- `GET /api/documents/{id}/download-url` checks current membership, status, role, and expiration, then issues a URL lasting at most 60 seconds and no longer than the remaining document lifetime.
- `GET /api/admin/compliance?day=2026/10/28` lists only the current tenant's archive keys. `cursor` continues the page.
- `GET /api/admin/compliance/retention?key=...` checks the current tenant's object retention. Deletion and retention shortening have no API.

Upload example:

```json
{"channelId":"30000000-0000-0000-0000-000000000001","filename":"policy.pdf","contentType":"application/pdf","sizeBytes":1024,"accessExpiresAt":"2027-01-01T00:00:00Z","allowedDepartmentIds":["20000000-0000-0000-0000-000000000001"],"minRoleRank":10}
```

PDF and UTF-8 text are indexed; PNG/JPEG/MP4 use the same permission/expiry controls without text extraction. Ingestion caps input at 50 MiB, PDF extraction at 1,000 pages, and extracted text at 2 million characters. Policy content is chunked into 1,200-character windows with 200-character overlap, deterministic vector IDs, and per-org custom shard selectors.

Presigned links are bearer capabilities until expiry. Department revocation prevents new links immediately; a previously issued link remains usable for its short signed lifetime. S3 checks signature expiration when a request starts, and an already-running download may continue. Deploy a streaming download proxy if policy requires terminating transfers at the exact deadline. Expiration is enforced on authorization and vector search; it does not delete the retained compliance archive.

## Isolation and regional topology

Deploy one complete cell per `eu-central-1`, `us-east-1`, and `ap-south-1`. Set `APP_REGION` and cell-local database, Redis, Kafka, Qdrant, and bucket endpoints. Never replicate chat/document payload stores across these cells. Regional auth metadata projections (organizations, users, memberships, channels) must be securely replicated to edge cells so an edge can authenticate and authorize its users. This repository consumes those projections; the provisioning/replication control plane is external.

`BACKBONE_REGIONS` maps region identifiers to allowlisted private service origins, for example `eu-central-1=https://eu.chat.internal,us-east-1=https://us.chat.internal,ap-south-1=https://ap.chat.internal`. The router uses HTTP/2 when supported and relays the original user JWT to `/internal/messages` in the organization's home cell. That endpoint revalidates user membership and channel permissions and rejects requests in the wrong cell. Restrict internal endpoints to private networks with TLS/mTLS.

For cross-region fanout, give each cell a short-lived workload JWT in `BACKBONE_TOKEN` with audience matching the receiving service, scope `chat.backbone`, and signed `source_region` matching that cell. `/internal/fanout` validates this scope and checks the source is the organization's home region. Refresh this configuration through the deployment's secret rotation process. The home cell publishes to its local Redis and forwards live fanout to other configured cells. Channel delivery rechecks current SQL permissions and JWT expiry. Remote fanout carries transient chat and targeted AI payloads; it does not persist a second regional chat history. Configure only the edge cells an organization's residency agreement permits.

PostgreSQL uses `ENABLE` and `FORCE ROW LEVEL SECURITY` on every tenant data table. Composite foreign keys prevent foreign-tenant membership links. The transaction interceptor runs after transaction creation and applies parameterized `set_config('app.org_id', ..., true)`, rejects a tenant switch inside a transaction, and fails closed without context. `open-in-view` is disabled. Transactions release their tenant setting on commit and rollback. TenantContext restores earlier scopes and clears at request/worker exit. A ThreadLocal is not propagated implicitly to async work; Kafka and WebSocket handlers establish explicit scopes.

Use a non-owner, NOSUPERUSER, NOBYPASSRLS runtime SQL role. Run Flyway using a separate migration identity before app startup; automatic Flyway is disabled by default. Compose initializes schema and grants a separate `chat_app` login. The infrastructure-only ingestion outbox intentionally has no RLS: the trusted dispatcher scans it across tenants, locks rows with SKIP LOCKED, synchronously publishes Kafka jobs, and deletes rows in the same SQL transaction. It contains identifiers only and has no user-facing read API. Restrict access to application/dispatcher identities. RLS assumes trusted server code; it does not protect against a compromised application identity changing its own SQL session settings.

## Messaging and recovery

There are exactly five application topics per region:

| Topic | Purpose |
|---|---|
| `chat.messages.inbound` | Authorized home-cell chat submission |
| `chat.messages.persisted` | Durable hot record ready for live delivery |
| `ai.suggestions.request` | User-targeted policy retrieval |
| `docs.ingestion.jobs` | Version-pinned document ingestion |
| `compliance.audit.stream` | Immutable archive materialization |

Every producer key is `orgId + ":" + channelId`. Keep partition counts fixed after launch if existing-key ordering must remain stable. Kafka's standard consumer-offsets/control topics are infrastructure topics. Production uses `KAFKA_REPLICAS=3`, min ISR=2, three or more brokers, TLS/SASL, broker ACLs, and no automatic topic creation. Only trusted producers may write the topics. Local Compose uses one broker and one replica for development.

Scylla uses `((org_id,channel_id,month_bucket),message_id)` with timeuuid clustering, LOCAL_QUORUM, LOCAL_SERIAL, and LWT INSERT IF NOT EXISTS. Every query contains the tenant partition key. Hot storage has a 90-day TTL; archive retention is five calendar years. Use RF=3 and matching datacenter names in production CQL. Set `SPRING_CASSANDRA_USERNAME` and `SPRING_CASSANDRA_PASSWORD` when database authentication is enabled; omit them for the local development cluster. Extremely active channels may need a future sub-bucket migration to bound partition sizes.

Delivery is at least once across SQL/Kafka/Scylla/Redis/S3. No consumer offset is acknowledged before its required work completes. The SQL ingestion outbox survives database/Kafka failures. Scylla persistence is replayable: the original record is retained and downstream events may repeat. Archive keys are deterministic per message, conditional PUT prevents another version on replay, and each write requests COMPLIANCE retention. Consumers retry failed records indefinitely without adding dead-letter topics. Monitor retry count, blocked partitions, oldest outbox age, consumer lag, and archive completeness. Operational remediation is needed for malformed records or permanent authorization/configuration failures. Broker retention is 14 days by default; size retention and recovery objectives so archiver lag cannot reach it.

AES-256-GCM envelopes bind tenant and message/purpose as associated data; KMS contexts bind wrapped DEKs to `org_id`. Caffeine keeps DEKs in process memory for five minutes with bounded caches. Each encryption uses a fresh random 96-bit nonce. KMS key automatic rotation preserves the ARN; replacing an organization's key ARN requires a controlled historical DEK rewrap/migration. Preserve old keys for retention. JVM heap dumps and process memory require operational access controls.

Qdrant searches always include the tenant shard selector and hard org/department/role/expiry/status predicates. SQL rechecks each candidate document, and streaming rechecks permissions and expiry for every delta. Vector payloads include policy text; secure Qdrant storage/networking and disable cross-cell backups. A partial ingestion cannot expose a document before SQL status READY. Policy excerpts are treated as untrusted evidence in the LLM prompt; model output is advisory and references source document IDs.

## Deployment limits and validation

The repository supplies the requested service and local dependencies. External OIDC, tenant provisioning/metadata replication, regional ingress/mTLS, AWS IAM/KMS/bucket provisioning, Qdrant tenant shard provisioning, approved model hosting, automated credential renewal, observability, quotas at ingress, malware scanning, disaster recovery, and load/capacity qualification belong to deployment infrastructure. Single-node Compose is for local development. Spring Boot 3.3.4 and the other requested pinned versions must receive a security/support review before production deployment.

The integration suite tests PostgreSQL RLS with the runtime role, cross-tenant reads and writes, foreign-key isolation, real JPA membership mapping, connection-pool reuse after commit/rollback, expiry, tenant scope restoration, mandatory vector filter contents, encryption tenant/key/purpose binding, archive retention/replay checks, and Redis subscription reference counting. AWS WORM, full regional failover, Kafka/Scylla recovery, real model streaming, and load characteristics need environment-level acceptance tests. Run `mvn verify` and `docker compose config --quiet` in CI; never interpret compilation alone as a compliance certification.
