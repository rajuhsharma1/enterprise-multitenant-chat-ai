package com.enterprise.chat.ws;

import com.enterprise.chat.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class LocalWebSocketSessionRegistry {
  private record Subscription(UUID org, UUID channel) {}

  private record Connected(WebSocketSession socket, TenantPrincipal principal, Instant expiresAt) {}

  private final Map<String, Connected> sessions = new ConcurrentHashMap<>();
  private final Map<Subscription, Set<String>> subscriptions = new HashMap<>();
  private final ChannelRbacValidator rbac;

  public LocalWebSocketSessionRegistry(ChannelRbacValidator rbac) {
    this.rbac = rbac;
  }

  public void register(WebSocketSession s, TenantPrincipal p, Instant expiresAt) {
    sessions.put(
        s.getId(),
        new Connected(new ConcurrentWebSocketSessionDecorator(s, 5000, 262144), p, expiresAt));
  }

  public synchronized boolean subscribe(String session, UUID org, UUID channel) {
    var c = sessions.get(session);
    if (c == null || !c.principal().orgId().equals(org)) throw ChannelRbacValidator.denied();
    Subscription key = new Subscription(org, channel);
    boolean first = !subscriptions.containsKey(key);
    long count = subscriptions.values().stream().filter(v -> v.contains(session)).count();
    if (!subscriptions.getOrDefault(key, Set.of()).contains(session) && count >= 100)
      throw new IllegalArgumentException("Subscription limit exceeded");
    subscriptions.computeIfAbsent(key, k -> new HashSet<>()).add(session);
    return first;
  }

  public synchronized boolean unsubscribe(String session, UUID org, UUID channel) {
    Subscription key = new Subscription(org, channel);
    var ids = subscriptions.get(key);
    if (ids == null || !ids.remove(session)) return false;
    if (ids.isEmpty()) {
      subscriptions.remove(key);
      return true;
    }
    return false;
  }

  public synchronized List<UUID> channels(String session, UUID org) {
    return subscriptions.entrySet().stream()
        .filter(e -> e.getKey().org().equals(org) && e.getValue().contains(session))
        .map(e -> e.getKey().channel())
        .toList();
  }

  public void remove(String id) {
    sessions.remove(id);
  }

  public void send(String id, String payload) {
    var c = sessions.get(id);
    if (c == null) return;
    try {
      c.socket().sendMessage(new TextMessage(payload));
    } catch (Exception e) {
      try {
        c.socket().close(CloseStatus.SESSION_NOT_RELIABLE);
      } catch (Exception ignored) {
      }
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
  public void expireSockets() {
    for (var c : sessions.values())
      if (!c.expiresAt().isAfter(Instant.now())) {
        try {
          c.socket().close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
        }
      }
  }

  public void deliver(RedisCrossRegionFanoutListener.Fanout event) {
    List<String> ids;
    synchronized (this) {
      ids =
          List.copyOf(
              subscriptions.getOrDefault(
                  new Subscription(event.orgId(), event.channelId()), Set.of()));
    }
    for (String id : ids) {
      var c = sessions.get(id);
      if (c == null
          || event.recipientId() != null && !event.recipientId().equals(c.principal().userId()))
        continue;
      if (!c.expiresAt().isAfter(Instant.now())) {
        try {
          c.socket().close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
        }
        continue;
      }
      try {
        TenantContext.with(
            c.principal(),
            () -> {
              rbac.require(event.channelId());
              return null;
            });
        send(id, event.payload());
      } catch (Exception e) {
        /* Revoked members receive no further payloads. */
      }
    }
  }
}
