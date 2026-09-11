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

    public record RegisterReq(@NotBlank String name, @NotBlank String slug,
                              @NotBlank String adminUsername, @NotBlank String adminPassword) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        var r = tenantService.register(req.name(), req.slug(), req.adminUsername(), req.adminPassword());
        return Map.of("tenantId", r.tenantId(), "adminUserId", r.adminUserId());
    }
}
