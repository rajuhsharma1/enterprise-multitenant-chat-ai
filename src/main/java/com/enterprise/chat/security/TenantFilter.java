package com.enterprise.chat.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantFilter extends OncePerRequestFilter {
  private final ChannelRbacValidator rbac;

  public TenantFilter(ChannelRbacValidator rbac) {
    this.rbac = rbac;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)
        || req.getRequestURI().equals("/internal/fanout")) {
      chain.doFilter(req, res);
      return;
    }
    TenantPrincipal principal;
    try {
      UUID org = UUID.fromString(token.getToken().getClaimAsString("org_id"));
      UUID user = UUID.fromString(token.getToken().getSubject());
      principal = TenantContext.withOrg(org, () -> rbac.loadPrincipal(user));
    } catch (RuntimeException e) {
      res.sendError(403, "Tenant identity rejected");
      return;
    }
    try {
      TenantContext.with(
          principal,
          () -> {
            try {
              chain.doFilter(req, res);
              return null;
            } catch (IOException | ServletException e) {
              throw new FilterFailure(e);
            }
          });
    } catch (FilterFailure e) {
      if (e.getCause() instanceof IOException io) throw io;
      throw (ServletException) e.getCause();
    }
  }

  private static final class FilterFailure extends RuntimeException {
    FilterFailure(Exception cause) {
      super(cause);
    }
  }
}
