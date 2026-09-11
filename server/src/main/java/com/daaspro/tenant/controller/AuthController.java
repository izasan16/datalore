package com.daaspro.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.entity.UserEntity;
import com.daaspro.tenant.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        UserEntity u = authService.login(req.username(), req.password());
        return Map.of(
                "token", StpUtil.getTokenValue(),
                "user", Map.of("id", u.getId(), "username", u.getUsername(),
                        "displayName", u.getDisplayName(), "role", u.getRole()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        authService.logout();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var c = TenantContext.get();
        return Map.of("userId", c.userId(), "username", c.username(),
                "role", c.role(), "tenantId", c.tenantId());
    }
}
