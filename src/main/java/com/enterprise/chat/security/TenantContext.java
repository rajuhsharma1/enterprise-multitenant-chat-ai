package com.enterprise.chat.security;

import java.util.UUID;
import java.util.function.Supplier;

public final class TenantContext {
  private record State(UUID orgId, TenantPrincipal principal) {}

  private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static UUID orgId() {
    State s = CURRENT.get();
    if (s == null) throw new IllegalStateException("Missing tenant scope");
    return s.orgId();
  }

  public static TenantPrincipal principal() {
    State s = CURRENT.get();
    if (s == null || s.principal() == null)
      throw new IllegalStateException("Missing tenant identity");
    return s.principal();
  }

  public static <T> T withOrg(UUID orgId, Supplier<T> work) {
    return scoped(new State(orgId, null), work);
  }

  public static <T> T with(TenantPrincipal principal, Supplier<T> work) {
    return scoped(new State(principal.orgId(), principal), work);
  }

  private static <T> T scoped(State next, Supplier<T> work) {
    State previous = CURRENT.get();
    try {
      CURRENT.set(next);
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
