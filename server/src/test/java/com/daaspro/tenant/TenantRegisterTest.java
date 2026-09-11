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
        var r = tenantService.register("测试租户A", "testa", "admin", "password123");
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, "tenant_" + r.tenantId());
        assertThat(n).isEqualTo(1);
        Integer admins = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE tenant_id = ? AND role = 'admin'",
                Integer.class, r.tenantId());
        assertThat(admins).isEqualTo(1);
    }
}
