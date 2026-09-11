package com.daaspro.tenant;

import com.daaspro.common.exception.BizException;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TenantRegisterTest {

    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void rejectsBadSlug() {
        assertThatThrownBy(() -> tenantService.register("测试", "Bad Slug!", "admin", "password123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("标识");
    }

    @Test
    void registersTenantAndCreatesSchema() {
        var r = tenantService.register("测试租户A", "testa", "admin_" + System.nanoTime(), "password123");
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, "tenant_testa");
        assertThat(n).isEqualTo(1);
        Integer admins = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE tenant_id = ? AND role = 'admin'",
                Integer.class, r.tenantId());
        assertThat(admins).isEqualTo(1);
    }

    @Test
    void autoSlugRegisterGeneratesUniqueSlug() {
        String suffix = String.valueOf(System.nanoTime());
        String slug1 = tenantService.registerAutoSlug("自动注册租户1", "admin1_" + suffix, "password123");
        String slug2 = tenantService.registerAutoSlug("自动注册租户2", "admin2_" + suffix, "password123");
        assertThat(slug1).isNotEqualTo(slug2).matches("^t[a-z0-9x]+$");
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tenant WHERE slug = ?", Integer.class, slug1);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void duplicateGlobalUsernameRejected() {
        String suffix = String.valueOf(System.nanoTime());
        tenantService.registerAutoSlug("重名租户1", "dup_" + suffix, "password123");
        assertThatThrownBy(() -> tenantService.registerAutoSlug("重名租户2", "dup_" + suffix, "password123"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名已被占用");
    }
}
