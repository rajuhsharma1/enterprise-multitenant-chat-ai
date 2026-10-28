package com.enterprise.chat.domain.nosql;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.data.cassandra.core.cql.*;
import org.springframework.data.cassandra.core.mapping.*;

@PrimaryKeyClass
public record ChatMessageKey(
    @PrimaryKeyColumn(name = "org_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED) UUID orgId,
    @PrimaryKeyColumn(name = "channel_id", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
        UUID channelId,
    @PrimaryKeyColumn(name = "month_bucket", ordinal = 2, type = PrimaryKeyType.PARTITIONED)
        String monthBucket,
    @PrimaryKeyColumn(
            name = "message_id",
            ordinal = 3,
            type = PrimaryKeyType.CLUSTERED,
            ordering = Ordering.DESCENDING)
        UUID messageId)
    implements Serializable {}
