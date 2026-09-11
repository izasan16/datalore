package com.daaspro.tenant.context;

public final class TenantContext {
    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    public record Ctx(Long tenantId, Long userId, String username, String role, String schemaName) {}

    public static void set(Ctx ctx) { HOLDER.set(ctx); }

    public static Ctx get() { return HOLDER.get(); }

    public static Long requireTenantId() {
        Ctx c = HOLDER.get();
        if (c == null) throw new IllegalStateException("no tenant context");
        return c.tenantId();
    }

    public static void clear() { HOLDER.remove(); }
}
