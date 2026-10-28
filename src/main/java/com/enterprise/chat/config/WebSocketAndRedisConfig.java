package com.enterprise.chat.config;

import com.enterprise.chat.ws.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.*;

@Configuration
public class WebSocketAndRedisConfig {
  @Bean
  RedisMessageListenerContainer redisListeners(RedisConnectionFactory factory) {
    var c = new RedisMessageListenerContainer();
    c.setConnectionFactory(factory);
    return c;
  }

  @Bean
  WebSocketConfigurer webSockets(
      EdgeWebSocketHandler handler,
      TenantHandshakeInterceptor handshake,
      @Value("${app.ws.allowed-origins}") String origins) {
    return registry ->
        registry
            .addHandler(handler, "/ws/chat")
            .addInterceptors(handshake)
            .setAllowedOrigins(origins.split(","));
  }

  @Configuration
  @EnableWebSocket
  static class Enable {}
}
