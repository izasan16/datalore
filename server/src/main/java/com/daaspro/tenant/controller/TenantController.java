package com.daaspro.tenant.controller;

import com.daaspro.tenant.service.TenantService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
@Validated
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    public record RegisterReq(@NotBlank String name,
                              @NotBlank String adminUsername, @NotBlank String adminPassword) {}

    /** 注册：slug 由服务端从名称生成，用户无感。返回 slug 便于前端紧接自动登录。 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        var slug = tenantService.registerAutoSlug(req.name(), req.adminUsername(), req.adminPassword());
        return Map.of("slug", slug, "adminUsername", req.adminUsername());
    }
}
