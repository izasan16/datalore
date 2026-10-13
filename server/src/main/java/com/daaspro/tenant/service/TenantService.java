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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public TenantService(TenantMapper tenantMapper, UserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
    }

    public record RegisterResult(Long tenantId, Long adminUserId) {}

    public Long getTenantIdBySlug(String slug) {
        TenantEntity t = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getSlug, slug));
        if (t == null) throw new BizException(404, "租户不存在");
        return t.getId();
    }

    /**
     * 用户无感注册：slug 由服务端生成（pinyin 首选不可用，直接用随机短码 + 名称原样保存）。
     * 名称重复时自动加后缀，不报错。
     */
    @Transactional
    public String registerAutoSlug(String name, String adminUsername, String adminPassword) {
        if (adminPassword == null || adminPassword.length() < 8) {
            throw new BizException(400, "管理员密码至少 8 位");
        }
        String slug;
        int attempt = 0;
        do {
            slug = "t" + Long.toString(System.currentTimeMillis(), 36) + (attempt == 0 ? "" : "x" + attempt);
            attempt++;
        } while (tenantMapper.selectCount(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getSlug, slug)) > 0);
        register(name, slug, adminUsername, adminPassword);
        return slug;
    }

    /**
     * slug 仅小写字母开头、小写字母/数字/下划线，3-32 位；用于 schema 名，防注入。
     * 名称或 slug 重复时抛业务异常（自动注册路径已去重）。
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
        // 用户名全局唯一（V5 约束的提前检查，给出可读错误）
        Long dupUser = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, adminUsername));
        if (dupUser > 0) {
            throw new BizException(400, "用户名已被占用，请换一个（用户名全局唯一）");
        }

        TenantEntity tenant = new TenantEntity();
        tenant.setName(name);
        tenant.setSlug(slug);
        tenant.setStatus(1);
        tenantMapper.insert(tenant);

        UserEntity admin = new UserEntity();
        admin.setTenantId(tenant.getId());
        admin.setUsername(adminUsername);
        admin.setPasswordHash(encoder.encode(adminPassword));
        admin.setDisplayName("管理员");
        admin.setRole("admin");
        admin.setStatus(1);
        userMapper.insert(admin);

        return new RegisterResult(tenant.getId(), admin.getId());
    }
}
