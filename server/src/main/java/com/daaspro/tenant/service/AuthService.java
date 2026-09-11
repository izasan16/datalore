package com.daaspro.tenant.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.tenant.entity.TenantEntity;
import com.daaspro.tenant.entity.UserEntity;
import com.daaspro.tenant.mapper.TenantMapper;
import com.daaspro.tenant.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, TenantMapper tenantMapper) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
    }

    /**
     * 登录标识 tenantSlug 即注册时的 slug（tenant.slug 列）。
     */
    public UserEntity login(String tenantSlug, String username, String password) {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getSlug, tenantSlug));
        if (tenant == null) throw new BizException(401, "租户、账号或密码错误");
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenant.getId())
                .eq(UserEntity::getUsername, username));
        if (user == null || !encoder.matches(password, user.getPasswordHash())) {
            throw new BizException(401, "租户、账号或密码错误");
        }
        if (user.getStatus() != 1 || tenant.getStatus() != 1) {
            throw new BizException(403, "账号或租户已停用");
        }
        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("tenantId", user.getTenantId());
        StpUtil.getSession().set("role", user.getRole());
        return user;
    }

    public void logout() {
        StpUtil.logout();
    }
}
