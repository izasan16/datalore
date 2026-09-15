package com.daaspro.sqllab;

import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SqlGatewayTest {

    @Autowired SqlGatewayService gateway;
    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;

    private String setupTenant() {
        String slug = "g" + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("网关租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin", "tenant_" + slug));
        var builtin = dataSourceService.list().stream()
                .filter(d -> Boolean.TRUE.equals(d.getBuiltin())).findFirst().orElseThrow();
        return String.valueOf(builtin.getId());
    }

    @Test
    void createInsertSelectRoundtrip() {
        String ds = setupTenant();
        gateway.run(ds, "CREATE TABLE demo(a int, b text)");
        gateway.run(ds, "INSERT INTO demo VALUES (1, 'x'), (2, 'y')");
        var r = gateway.run(ds, "SELECT a, b FROM demo ORDER BY a");
        assertThat(r.columns()).containsExactly("a", "b");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0)).containsExactly("1", "x");
        assertThat(r.isQuery()).isTrue();
    }

    @Test
    void rejectsMultiStatement() {
        String ds = setupTenant();
        assertThatThrownBy(() -> gateway.run(ds, "select 1; drop table demo"))
                .isInstanceOf(BizException.class).hasMessageContaining("单条");
    }

    @Test
    void rejectsDangerousPatterns() {
        String ds = setupTenant();
        assertThatThrownBy(() -> gateway.run(ds, "select * from information_schema.tables"))
                .isInstanceOf(BizException.class).hasMessageContaining("禁止");
        assertThatThrownBy(() -> gateway.run(ds, "select pg_sleep(1)"))
                .isInstanceOf(BizException.class).hasMessageContaining("禁止");
        assertThatThrownBy(() -> gateway.run(ds, "select * from public.sys_user"))
                .isInstanceOf(BizException.class).hasMessageContaining("禁止");
        // 注释绕过无效
        assertThatThrownBy(() -> gateway.run(ds, "select /* pg_sleep */ * from pg_catalog.pg_tables"))
                .isInstanceOf(BizException.class).hasMessageContaining("禁止");
    }

    @Test
    void stringLiteralsNotFalseBlocked() {
        String ds = setupTenant();
        var r = gateway.run(ds, "select 'tenant_9999' as t, '-- not a comment' as c, 'pg_' as p");
        assertThat(r.rows().get(0)).containsExactly("tenant_9999", "-- not a comment", "pg_");
    }

    @Test
    void crossTenantSchemaRejected() {
        String dsA = setupTenant();
        String slugB = "other" + (System.nanoTime() % 100000);
        long tidB = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("他租户" + slugB, "admb" + slugB, "password123"));
        assertThatThrownBy(() -> gateway.run(dsA, "select * from tenant_" + slugB + ".x"))
                .isInstanceOf(BizException.class).hasMessageContaining("其他租户");
        assertThat(tidB).isGreaterThan(0);
    }

    @Test
    void externalSourceReadOnly() {
        String ds = setupTenant();
        var ext = dataSourceService.create("平台PG", "postgresql", "localhost", 15432,
                "daaspro", "public", "daaspro", "daaspro_dev_2026");
        String extId = String.valueOf(ext.getId());
        var ok = gateway.run(extId, "select 1 as one");
        assertThat(ok.rows().get(0)).containsExactly("1");
        assertThatThrownBy(() -> gateway.run(extId, "CREATE TABLE hacked(a int)"))
                .isInstanceOf(BizException.class).hasMessageContaining("仅支持查询");
    }

    @Test
    void sqlErrorPropagates() {
        String ds = setupTenant();
        assertThatThrownBy(() -> gateway.run(ds, "select * from not_exists"))
                .isInstanceOf(BizException.class).hasMessageContaining("SQL 错误");
    }
}
