package com.enterprise.chat.config;

import com.enterprise.chat.security.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Component
@Order(100)
public class PostgresRlsAspect {
  private final JdbcTemplate jdbc;

  public PostgresRlsAspect(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
  public Object tenantTransaction(ProceedingJoinPoint point) throws Throwable {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Tenant SQL requires an active transaction");
    String tenant = TenantContext.orgId().toString();
    String existing =
        jdbc.queryForObject("select current_setting('app.org_id', true)", String.class);
    if (existing != null && !existing.isBlank() && !existing.equals(tenant))
      throw new SecurityException("Cannot change tenant within a transaction");
    jdbc.queryForObject("select set_config('app.org_id', ?, true)", String.class, tenant);
    return point.proceed();
  }
}
