package com.datalore.datasource;

import com.datalore.common.exception.BizException;
import com.datalore.datasource.service.DataSourceService;
import com.datalore.tenant.context.TenantContext;
import com.datalore.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class DataSourceCrudTest {

    @Autowired TenantService tenantService;
    @Autowired DataSourceService service;

    private long newTenant(String prefix) {
        var slug = tenantService.registerAutoSlug("租户" + prefix + System.nanoTime(), "adm_" + prefix + System.nanoTime(), "password123");
        var t = tenantService.getTenantIdBySlug(slug);
        TenantContext.set(new TenantContext.Ctx(t, 1L, "admin", "admin"));
        return t;
    }

    @Test
    void createListDelete() {
        newTenant("dslist");
        var ds = service.create("本地PG", "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        assertThat(ds.getId()).isNotNull();
        assertThat(service.list()).anyMatch(d -> d.getId().equals(ds.getId()));

        service.delete(ds.getId());
        assertThat(service.list()).noneMatch(d -> d.getId().equals(ds.getId()));
    }

    @Test
    void testConnectivityToPlatformPg() {
        newTenant("dsok");
        var ds = service.create("连通测试", "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        service.test(ds.getId());
    }

    @Test
    void wrongPasswordFails() {
        newTenant("dsbad");
        var ds = service.create("错误密码", "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "wrong-password", null);
        assertThatThrownBy(() -> service.test(ds.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("连接失败");
    }

    @Test
    void rejectsUnknownType() {
        newTenant("dstype");
        assertThatThrownBy(() -> service.create("x", "sqlite", "h", 1, "d", null, "u", "p", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void crossTenantAccessDenied() {
        long a = newTenant("dsisoa");
        long b = newTenant("dsisob");
        TenantContext.set(new TenantContext.Ctx(a, 1L, "admin", "admin"));
        var ds = service.create("隔离", "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        TenantContext.set(new TenantContext.Ctx(b, 2L, "admin", "admin"));
        assertThatThrownBy(() -> service.getOwned(ds.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        TenantContext.clear();
    }
}
