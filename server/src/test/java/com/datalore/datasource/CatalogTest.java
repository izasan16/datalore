package com.datalore.datasource;

import com.datalore.datasource.service.DataSourceService;
import com.datalore.tenant.context.TenantContext;
import com.datalore.tenant.service.TenantService;
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
    void newTenantHasNoDefaultSourceAndExternalBrowsable() {
        String slug = tenantService.registerAutoSlug("目录租户" + System.nanoTime(), "admcat" + System.nanoTime(), "password123");
        Long tid = tenantService.getTenantIdBySlug(slug);

        TenantContext.set(new TenantContext.Ctx(tid, 1L, "admin", "admin"));
        assertThat(dataSourceService.list()).isEmpty();

        var ds = dataSourceService.create("目录PG" + slug, "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        assertThat(dataSourceService.listSchemas(ds.getId())).isNotEmpty();
        assertThat(dataSourceService.listTables(ds.getId(), null)).isNotNull();

        TenantContext.clear();
    }
}
