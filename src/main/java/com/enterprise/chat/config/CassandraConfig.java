package com.enterprise.chat.config;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.config.*;
import org.springframework.boot.autoconfigure.cassandra.DriverConfigLoaderBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class CassandraConfig {
  @Bean
  DriverConfigLoaderBuilderCustomizer consistency() {
    return b ->
        b.withString(DefaultDriverOption.REQUEST_CONSISTENCY, ConsistencyLevel.LOCAL_QUORUM.name())
            .withString(
                DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY,
                ConsistencyLevel.LOCAL_SERIAL.name())
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, java.time.Duration.ofSeconds(10));
  }
}
