package com.daaspro.sqllab;

import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 独立 context：maxRows=5、maxSeconds=2，验证网关硬限制生效。 */
@SpringBootTest(properties = {"daaspro.sql.max-rows=5", "daaspro.sql.max-seconds=2"})
@Transactional
class SqlGatewayLimitsTest {

    @Autowired SqlGatewayService gateway;
    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;

    private String setupTenant() {
        String slug = "lm" + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("限额租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin"));
        var ds = dataSourceService.create("限额PG" + slug, "postgresql", "localhost", 15432,
                "daaspro", "public", "daaspro", "daaspro_dev_2026");
        return String.valueOf(ds.getId());
    }

    @Test
    void truncatesAtMaxRows() {
        String ds = setupTenant();
        var r = gateway.run(ds, "SELECT generate_series(1, 100) AS n");
        assertThat(r.rows()).hasSize(5);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void killsSlowQuery() {
        String ds = setupTenant();
        assertThatThrownBy(() -> gateway.run(ds,
                "SELECT sum(g) FROM generate_series(1, 200000000) g"))
                .isInstanceOf(com.daaspro.common.exception.BizException.class)
                .hasMessageContaining("超时");
    }
}
