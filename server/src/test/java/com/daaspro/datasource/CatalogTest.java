package com.daaspro.datasource;

import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CatalogTest {

    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;

    @Test
    void builtinAutoRegisteredAndBrowsable() {
        String slug = "cat" + System.nanoTime() % 100000;
        var r = tenantService.register("目录租户" + slug, slug, "admin", "password123");

        TenantContext.set(new TenantContext.Ctx(r.tenantId(), 1L, "admin", "admin", "tenant_" + slug));
        var list = dataSourceService.list();
        assertThat(list).anyMatch(d -> Boolean.TRUE.equals(d.getBuiltin()));
        var builtin = list.stream().filter(d -> Boolean.TRUE.equals(d.getBuiltin())).findFirst().orElseThrow();

        var tables = dataSourceService.listTables(builtin.getId());
        assertThat(tables).isEmpty();

        TenantContext.clear();
    }
}
