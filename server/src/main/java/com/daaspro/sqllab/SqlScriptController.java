package com.daaspro.sqllab;

import com.daaspro.sqllab.entity.SqlScriptEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scripts")
@Validated
public class SqlScriptController {

    private final SqlScriptService service;

    public SqlScriptController(SqlScriptService service) {
        this.service = service;
    }

    public record CreateReq(@NotBlank String name, String content) {}

    public record UpdateReq(String name, String content) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list().stream().map(s -> Map.<String, Object>of(
                "id", s.getId(), "name", s.getName(),
                "updatedAt", s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString())).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        var s = service.get(id);
        return Map.of("id", s.getId(), "name", s.getName(), "content", s.getContent(),
                "updatedAt", s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateReq req) {
        var s = service.create(req.name(), req.content());
        return Map.of("id", s.getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        var s = service.update(id, req.name(), req.content());
        return Map.of("id", s.getId(), "updatedAt", s.getUpdatedAt().toString());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
