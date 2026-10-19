package com.datalore.tenant.controller;

import com.datalore.tenant.entity.UserEntity;
import com.datalore.tenant.service.UserService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record CreateReq(@NotBlank String username, @NotBlank String password,
                            String displayName, String role) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return userService.list().stream()
                .map(u -> Map.<String, Object>of("id", u.getId(), "username", u.getUsername(),
                        "displayName", u.getDisplayName(), "role", u.getRole(), "status", u.getStatus()))
                .toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateReq req) {
        UserEntity u = userService.create(req.username(), req.password(), req.displayName(), req.role());
        return Map.of("id", u.getId());
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable Long id) {
        userService.disable(id);
        return Map.of("ok", true);
    }
}
