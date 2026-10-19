package com.datalore.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datalore.common.exception.BizException;
import com.datalore.tenant.context.TenantContext;
import com.datalore.tenant.entity.UserEntity;
import com.datalore.tenant.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<UserEntity> list() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, TenantContext.requireTenantId())
                .orderByAsc(UserEntity::getId));
    }

    public UserEntity create(String username, String password, String displayName, String role) {
        if (!"admin".equals(TenantContext.get().role())) {
            throw new BizException(403, "仅管理员可操作");
        }
        if (password == null || password.length() < 8) {
            throw new BizException(400, "密码至少 8 位");
        }
        if (!"admin".equals(role) && !"member".equals(role)) {
            throw new BizException(400, "角色非法");
        }
        Long dup = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, TenantContext.requireTenantId())
                .eq(UserEntity::getUsername, username));
        if (dup > 0) throw new BizException(400, "用户名已存在");

        UserEntity u = new UserEntity();
        u.setTenantId(TenantContext.requireTenantId());
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(displayName == null ? username : displayName);
        u.setRole(role);
        u.setStatus(1);
        userMapper.insert(u);
        return u;
    }

    public void disable(Long userId) {
        if (!"admin".equals(TenantContext.get().role())) {
            throw new BizException(403, "仅管理员可操作");
        }
        UserEntity u = userMapper.selectById(userId);
        if (u == null || !u.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "用户不存在");
        }
        if (u.getId().equals(TenantContext.get().userId())) {
            throw new BizException(400, "不能停用自己");
        }
        u.setStatus(0);
        userMapper.updateById(u);
    }
}
