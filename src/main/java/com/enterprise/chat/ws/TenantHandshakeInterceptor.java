package com.enterprise.chat.ws;

import com.enterprise.chat.security.TenantContext;
import java.util.Map;
import org.springframework.http.server.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class TenantHandshakeInterceptor implements HandshakeInterceptor {
  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attrs) {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken auth)) return false;
    attrs.put("tenant", TenantContext.principal());
    attrs.put("bearer", auth.getToken().getTokenValue());
    attrs.put("expiresAt", auth.getToken().getExpiresAt());
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception e) {}
}
