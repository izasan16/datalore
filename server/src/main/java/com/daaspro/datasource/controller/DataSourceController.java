package com.daaspro.datasource.controller;

import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.DataSourceService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
@Validated
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    public record CreateReq(@NotBlank String name, @NotBlank String type, @NotBlank String host,
                            Integer port, @NotBlank String databaseName, String schemaName,
                            @NotBlank String username, @NotBlank String password) {}

    public record TestReq(@NotBlank String type, @NotBlank String host, Integer port,
                          @NotBlank String databaseName, String schemaName,
                          @NotBlank String username, @NotBlank String password) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list().stream().map(ds -> Map.<String, Object>of(
                "id", ds.getId(), "name", ds.getName(), "type", ds.getType(),
                "host", ds.getHost() == null ? "" : ds.getHost(),
                "port", ds.getPort() == null ? 0 : ds.getPort(),
                "databaseName", ds.getDatabaseName() == null ? "" : ds.getDatabaseName(),
                "schemaName", ds.getSchemaName() == null ? "" : ds.getSchemaName(),
                "status", ds.getStatus())).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateReq req) {
        var ds = service.create(req.name(), req.type(), req.host(), req.port(),
                req.databaseName(), req.schemaName(), req.username(), req.password());
        return Map.of("id", ds.getId());
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        service.test(id);
        return Map.of("ok", true);
    }

    @PostMapping("/test")
    public Map<String, Object> testRaw(@RequestBody TestReq req) {
        service.testConnection(req.type(), req.host(), req.port(), req.databaseName(),
                req.schemaName(), req.username(), req.password());
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/tables")
    public List<Map<String, String>> tables(@PathVariable Long id,
                                            @RequestParam(required = false) String schema) {
        return service.listTables(id, schema);
    }

    @GetMapping("/{id}/tables/{table}/columns")
    public List<Map<String, String>> columns(@PathVariable Long id, @PathVariable String table,
                                             @RequestParam(required = false) String schema) {
        return service.listColumns(id, table, schema);
    }

    @GetMapping("/{id}/schemas")
    public List<Map<String, String>> schemas(@PathVariable Long id) {
        return service.listSchemas(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
