package com.daaspro.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.tenant.entity.TenantEntity;
import com.daaspro.tenant.entity.UserEntity;
import com.daaspro.tenant.mapper.TenantMapper;
import com.daaspro.tenant.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class TenantService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,31}$");

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final SchemaProvisioner schemaProvisioner;
    private final com.daaspro.datasource.mapper.DataSourceMapper dataSourceMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public TenantService(TenantMapper tenantMapper, UserMapper userMapper,
                         SchemaProvisioner schemaProvisioner,
                         com.daaspro.datasource.mapper.DataSourceMapper dataSourceMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.schemaProvisioner = schemaProvisioner;
        this.dataSourceMapper = dataSourceMapper;
    }

    public record RegisterResult(Long tenantId, Long adminUserId) {}

    /**
     * slug 仅小写字母开头、小写字母/数字/下划线，3-32 位；用于 schema 名，防注入。
     */
    @Transactional
    public RegisterResult register(String name, String slug, String adminUsername, String adminPassword) {
        if (!NAME_PATTERN.matcher(slug).matches()) {
            throw new BizException(400, "标识只能以小写字母开头，含小写字母/数字/下划线，3-32 位");
        }
        if (adminPassword == null || adminPassword.length() < 8) {
            throw new BizException(400, "管理员密码至少 8 位");
        }
        Long count = tenantMapper.selectCount(
                new LambdaQueryWrapper<TenantEntity>()
                        .eq(TenantEntity::getName, name)
                        .or().eq(TenantEntity::getSlug, slug));
        if (count > 0) {
            throw new BizException(400, "租户名或标识已存在");
        }

        TenantEntity tenant = new TenantEntity();
        tenant.setName(name);
        tenant.setSlug(slug);
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        // schema 名与登录 slug 一一对应（slug 格式已校验，无注入面）
        String schemaName = "tenant_" + slug;
        tenant.setSchemaName(schemaName);
        tenantMapper.updateById(tenant);
        schemaProvisioner.createSchema(schemaName);

        UserEntity admin = new UserEntity();
        admin.setTenantId(tenant.getId());
        admin.setUsername(adminUsername);
        admin.setPasswordHash(encoder.encode(adminPassword));
        admin.setDisplayName("管理员");
        admin.setRole("admin");
        admin.setStatus(1);
        userMapper.insert(admin);

        // 自动注册内置数据源（指向租户数据区 schema）
        com.daaspro.datasource.entity.DataSourceEntity builtin = new com.daaspro.datasource.entity.DataSourceEntity();
        builtin.setTenantId(tenant.getId());
        builtin.setName("内置数据源");
        builtin.setType("postgresql");
        builtin.setBuiltin(true);
        builtin.setSchemaName(schemaName);
        builtin.setStatus(1);
        dataSourceMapper.insert(builtin);

        return new RegisterResult(tenant.getId(), admin.getId());
    }
}
