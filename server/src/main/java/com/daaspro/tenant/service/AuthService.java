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

import java.util.List;

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
     * 登录：用户名全局查找所属租户（当前 MVP 假定用户名全局唯一；
     * 同名冲突时由注册侧避免）。多租户同名用户后续用邮箱/手机号区分。
     */
    public UserEntity login(String username, String password) {
        List<UserEntity> users = userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        UserEntity user = users.stream()
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .findFirst()
                .orElse(null);
        if (user == null) throw new BizException(401, "账号或密码错误");
        TenantEntity tenant = tenantMapper.selectById(user.getTenantId());
        if (tenant == null || tenant.getStatus() != 1) {
            throw new BizException(403, "账号所在租户已停用");
        }
        if (user.getStatus() != 1) {
            throw new BizException(403, "账号已停用");
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
