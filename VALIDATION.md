# Validation

- Repository: enterprise-multitenant-chat-ai, 55 source/configuration files matching the requested tree.
- Built with JDK 21.0.9 and Maven 3.9.9 using Spring Boot 3.3.4.
- `mvn clean verify` passed, followed by `mvn verify` after final fixes: 10 tests, zero failures/errors/skips.
- PostgreSQL 16 Testcontainers tests cover a real non-superuser runtime role and RLS, pooled transaction cleanup, foreign-key tenant isolation, actual JPA mapping, document expiration, encryption binding, mandatory vector filters, archive retention/replay, and subscription reference counts.
- Docker Compose configuration validated. PostgreSQL and Scylla initialization containers exited successfully.
- Full application started against PostgreSQL, Scylla, Redis, Kafka, and Qdrant; the unauthenticated history endpoint returned HTTP 401.
- Kafka contained exactly the five application topics plus Kafka internal consumer offsets.
- Source ZIP excludes generated Maven target files. The executable JAR is supplied separately.
- AWS Object Lock enforcement, live OIDC, model API responses, cross-region failure recovery, and load/capacity behavior require deployment-level acceptance testing. See README for setup and limits.
