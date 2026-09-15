package com.daaspro.sqllab;

import com.daaspro.common.exception.BizException;
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
class SqlScriptTest {

    @Autowired SqlScriptService service;
    @Autowired TenantService tenantService;

    private long setupTenant() {
        String slug = "sc" + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("脚本租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin", "tenant_" + slug));
        return tid;
    }

    @Test
    void createListGetUpdateDelete() {
        setupTenant();
        var s = service.create("日报查询", "select 1");
        assertThat(service.list()).anyMatch(x -> x.getId().equals(s.getId()));
        assertThat(service.get(s.getId()).getContent()).isEqualTo("select 1");

        service.update(s.getId(), "改名", "select 2");
        assertThat(service.get(s.getId()).getName()).isEqualTo("改名");
        assertThat(service.get(s.getId()).getContent()).isEqualTo("select 2");

        service.delete(s.getId());
        assertThatThrownBy(() -> service.get(s.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在");
    }

    @Test
    void duplicateNameRejected() {
        setupTenant();
        service.create("同名", "select 1");
        assertThatThrownBy(() -> service.create("同名", "select 2"))
                .isInstanceOf(BizException.class).hasMessageContaining("已存在");
    }

    @Test
    void crossTenantDenied() {
        long a = setupTenant();
        var s = service.create("A脚本", "select 1");
        String slugB = "scb" + (System.nanoTime() % 100000000);
        long b = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("B租户" + slugB, "admb" + slugB, "password123"));
        TenantContext.set(new TenantContext.Ctx(b, 2L, "admb", "admin", "tenant_" + slugB));
        assertThat(service.list()).noneMatch(x -> x.getId().equals(s.getId()));
        assertThatThrownBy(() -> service.get(s.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在");
        assertThat(a).isLessThan(b);
        TenantContext.clear();
    }
}
