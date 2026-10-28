package com.enterprise.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCrossRegionFanoutListener implements MessageListener {
  public record Fanout(UUID orgId, UUID channelId, UUID recipientId, String payload) {}

  private final LocalWebSocketSessionRegistry sessions;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;

  public RedisCrossRegionFanoutListener(
      LocalWebSocketSessionRegistry sessions, ObjectMapper json, StringRedisTemplate redis) {
    this.sessions = sessions;
    this.json = json;
    this.redis = redis;
  }

  public static String topic(UUID org, UUID channel) {
    return "ws:fanout:" + org + ":" + channel;
  }

  public void publish(Fanout event) {
    try {
      redis.convertAndSend(topic(event.orgId(), event.channelId()), json.writeValueAsString(event));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      Fanout event = json.readValue(message.getBody(), Fanout.class);
      if (!topic(event.orgId(), event.channelId())
          .equals(new String(message.getChannel(), StandardCharsets.UTF_8))) return;
      sessions.deliver(event);
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Fanout delivery failed: {}", e.getClass().getSimpleName());
    }
  }
}
