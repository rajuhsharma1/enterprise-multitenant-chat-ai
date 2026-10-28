package com.enterprise.chat.ws;

import com.enterprise.chat.domain.dto.InboundWsFrame;
import com.enterprise.chat.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.listener.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class EdgeWebSocketHandler extends TextWebSocketHandler {
  private final LocalWebSocketSessionRegistry sessions;
  private final HomeRegionBackboneRouter router;
  private final ChannelRbacValidator rbac;
  private final RedisMessageListenerContainer redis;
  private final RedisCrossRegionFanoutListener listener;
  private final ObjectMapper json;
  private final Validator validator;

  public EdgeWebSocketHandler(
      LocalWebSocketSessionRegistry sessions,
      HomeRegionBackboneRouter router,
      ChannelRbacValidator rbac,
      RedisMessageListenerContainer redis,
      RedisCrossRegionFanoutListener listener,
      ObjectMapper json,
      Validator validator) {
    this.sessions = sessions;
    this.router = router;
    this.rbac = rbac;
    this.redis = redis;
    this.listener = listener;
    this.json = json;
    this.validator = validator;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    s.setTextMessageSizeLimit(65536);
    sessions.register(
        s,
        (TenantPrincipal) s.getAttributes().get("tenant"),
        (Instant) s.getAttributes().get("expiresAt"));
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
    try {
      if (!((Instant) s.getAttributes().get("expiresAt")).isAfter(Instant.now())) {
        s.close(CloseStatus.POLICY_VIOLATION);
        return;
      }
      var frame = json.readValue(message.getPayload(), InboundWsFrame.class);
      if (!validator.validate(frame).isEmpty()) throw new IllegalArgumentException("Invalid frame");
      TenantPrincipal p = (TenantPrincipal) s.getAttributes().get("tenant");
      TenantContext.with(
          p,
          () -> {
            rbac.require(frame.channelId());
            switch (frame.type()) {
              case SUBSCRIBE -> {
                synchronized (sessions) {
                  if (sessions.subscribe(s.getId(), p.orgId(), frame.channelId()))
                    redis.addMessageListener(
                        listener,
                        new ChannelTopic(
                            RedisCrossRegionFanoutListener.topic(p.orgId(), frame.channelId())));
                }
              }
              case UNSUBSCRIBE -> unsubscribe(s.getId(), p.orgId(), frame.channelId());
              case MESSAGE -> {
                var event = router.route(frame, (String) s.getAttributes().get("bearer"));
                try {
                  sessions.send(
                      s.getId(),
                      json.writeValueAsString(
                          Map.of(
                              "type",
                              "ACK",
                              "messageId",
                              event.messageId(),
                              "requestId",
                              frame.requestId())));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }
            }
            return null;
          });
    } catch (Exception e) {
      sessions.send(
          s.getId(),
          "{\"type\":\"ERROR\",\"message\":\"Request rejected or temporarily unavailable\"}");
    }
  }

  private void unsubscribe(String session, UUID org, UUID channel) {
    synchronized (sessions) {
      if (sessions.unsubscribe(session, org, channel))
        redis.removeMessageListener(
            listener, new ChannelTopic(RedisCrossRegionFanoutListener.topic(org, channel)));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    var p = (TenantPrincipal) s.getAttributes().get("tenant");
    if (p != null)
      for (var channel : sessions.channels(s.getId(), p.orgId()))
        unsubscribe(s.getId(), p.orgId(), channel);
    sessions.remove(s.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession s, Throwable error) throws Exception {
    s.close(CloseStatus.SERVER_ERROR);
  }
}
