package com.datalore.sqllab;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datalore.common.exception.BizException;
import com.datalore.sqllab.entity.SqlScriptEntity;
import com.datalore.sqllab.mapper.SqlScriptMapper;
import com.datalore.tenant.context.TenantContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SqlScriptService {

    private final SqlScriptMapper mapper;

    public SqlScriptService(SqlScriptMapper mapper) {
        this.mapper = mapper;
    }

    public List<SqlScriptEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<SqlScriptEntity>()
                .select(SqlScriptEntity::getId, SqlScriptEntity::getName, SqlScriptEntity::getUpdatedAt)
                .eq(SqlScriptEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(SqlScriptEntity::getUpdatedAt));
    }

    public SqlScriptEntity get(Long id) {
        SqlScriptEntity s = mapper.selectById(id);
        if (s == null || !s.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "脚本不存在");
        }
        return s;
    }

    public SqlScriptEntity create(String name, String content) {
        requireName(name);
        Long dup = mapper.selectCount(new LambdaQueryWrapper<SqlScriptEntity>()
                .eq(SqlScriptEntity::getTenantId, TenantContext.requireTenantId())
                .eq(SqlScriptEntity::getName, name));
        if (dup > 0) throw new BizException(400, "同名脚本已存在");
        SqlScriptEntity s = new SqlScriptEntity();
        s.setTenantId(TenantContext.requireTenantId());
        s.setName(name);
        s.setContent(content == null ? "" : content);
        s.setUpdatedAt(LocalDateTime.now());
        mapper.insert(s);
        return s;
    }

    public SqlScriptEntity update(Long id, String name, String content) {
        SqlScriptEntity s = get(id);
        if (name != null && !name.equals(s.getName())) {
            requireName(name);
            Long dup = mapper.selectCount(new LambdaQueryWrapper<SqlScriptEntity>()
                    .eq(SqlScriptEntity::getTenantId, TenantContext.requireTenantId())
                    .eq(SqlScriptEntity::getName, name)
                    .ne(SqlScriptEntity::getId, id));
            if (dup > 0) throw new BizException(400, "同名脚本已存在");
            s.setName(name);
        }
        if (content != null) s.setContent(content);
        s.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(s);
        return s;
    }

    public void delete(Long id) {
        SqlScriptEntity s = get(id);
        mapper.deleteById(s.getId());
    }

    private void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new BizException(400, "脚本名称不合法");
        }
    }
}
