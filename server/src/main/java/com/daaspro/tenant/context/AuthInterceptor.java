package com.daaspro.tenant.context;

import cn.dev33.satoken.stp.StpUtil;
import com.daaspro.tenant.entity.TenantEntity;
import com.daaspro.tenant.mapper.TenantMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TenantMapper tenantMapper;

    public AuthInterceptor(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!StpUtil.isLogin()) {
            response.setStatus(401);
            return false;
        }
        long userId = StpUtil.getLoginIdAsLong();
        String username = (String) StpUtil.getSession().get("username");
        Long tenantId = (Long) StpUtil.getSession().get("tenantId");
        String role = (String) StpUtil.getSession().get("role");
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStatus() != 1) {
            response.setStatus(401);
            return false;
        }
        TenantContext.set(new TenantContext.Ctx(tenantId, userId, username, role));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        TenantContext.clear();
    }
}
