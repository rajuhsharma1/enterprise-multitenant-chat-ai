CREATE KEYSPACE IF NOT EXISTS enterprise_chat
WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 1};
-- Production: use the local datacenter name and RF=3; never replicate this keyspace across cells.
CREATE TABLE IF NOT EXISTS enterprise_chat.chat_messages (
    org_id uuid, channel_id uuid, month_bucket text, message_id timeuuid,
    sender_id uuid, created_at timestamp, encrypted_content text, document_id uuid,
    PRIMARY KEY ((org_id,channel_id,month_bucket),message_id)
) WITH CLUSTERING ORDER BY (message_id DESC)
AND compaction = {'class':'TimeWindowCompactionStrategy','compaction_window_unit':'DAYS','compaction_window_size':'3'}
AND default_time_to_live = 7776000;
