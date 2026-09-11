package com.daaspro.tenant.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SchemaProvisioner {

    private final JdbcTemplate jdbc;

    public SchemaProvisioner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** schema 名由 TenantService 校验过格式（tenant_ + 数字），无注入面。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createSchema(String schemaName) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
    }
}
