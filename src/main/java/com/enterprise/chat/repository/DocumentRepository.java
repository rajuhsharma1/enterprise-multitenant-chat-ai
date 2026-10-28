package com.enterprise.chat.repository;

import com.enterprise.chat.domain.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, java.util.UUID> {

  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT * FROM documents WHERE id=:id AND org_id=:org AND access_expires_at >"
              + " GREATEST(statement_timestamp(), :now) AND status='READY'",
      nativeQuery = true)
  java.util.Optional<DocumentEntity> findAccessible(
      java.util.UUID id, java.util.UUID org, java.time.Instant now);
}
