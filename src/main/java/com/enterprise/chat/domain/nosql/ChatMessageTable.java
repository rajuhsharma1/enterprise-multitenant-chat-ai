package com.enterprise.chat.domain.nosql;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.cassandra.core.mapping.*;

@Table("chat_messages")
public record ChatMessageTable(
    @PrimaryKey ChatMessageKey key,
    @Column("sender_id") UUID senderId,
    @Column("created_at") Instant createdAt,
    @Column("encrypted_content") String encryptedContent,
    @Column("document_id") UUID documentId) {}
