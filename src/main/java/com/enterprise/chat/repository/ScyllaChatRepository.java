package com.enterprise.chat.repository;

import com.enterprise.chat.domain.nosql.*;
import java.util.*;
import org.springframework.data.cassandra.repository.*;

public interface ScyllaChatRepository
    extends CassandraRepository<ChatMessageTable, ChatMessageKey> {
  @Query(
      "SELECT * FROM chat_messages WHERE org_id=?0 AND channel_id=?1 AND month_bucket=?2 AND"
          + " message_id < ?3 LIMIT ?4")
  List<ChatMessageTable> history(
      UUID orgId, UUID channelId, String monthBucket, UUID before, int limit);

  @Query(
      "SELECT * FROM chat_messages WHERE org_id=?0 AND channel_id=?1 AND month_bucket=?2 LIMIT ?3")
  List<ChatMessageTable> latest(UUID orgId, UUID channelId, String monthBucket, int limit);
}
