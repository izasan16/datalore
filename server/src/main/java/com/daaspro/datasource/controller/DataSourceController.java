package com.daaspro.datasource.controller;

import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.datasource.service.TableDataService;
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
    private final TableDataService tableDataService;

    public DataSourceController(DataSourceService service, TableDataService tableDataService) {
        this.service = service;
        this.tableDataService = tableDataService;
    }

    public record CreateReq(@NotBlank String name, @NotBlank String type, @NotBlank String host,
                            Integer port, @NotBlank String databaseName, String schemaName,
                            @NotBlank String username, @NotBlank String password) {}

    public record UpdateReq(String name, String host, Integer port, String databaseName,
                            String schemaName, String username, String password) {}

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

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        var ds = service.update(id, req.name(), req.host(), req.port(),
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

    /** 数据 Tab：分页 + 筛选 + 排序 + 行标识（PG ctid / 其他库主键）。 */
    @GetMapping("/{id}/tables/rows")
    public Map<String, Object> rows(@PathVariable Long id,
                                    @RequestParam(required = false) String schema,
                                    @RequestParam String table,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int pageSize,
                                    @RequestParam(required = false) String filters,
                                    @RequestParam(required = false) String orderBy,
                                    @RequestParam(required = false) String orderDir) {
        return tableDataService.rows(id, schema, table, page, pageSize, filters, orderBy, orderDir);
    }

    /** 数据 Tab 导出：按筛选取最多 1 万行。 */
    @GetMapping("/{id}/tables/rows/export")
    public Map<String, Object> exportRows(@PathVariable Long id,
                                          @RequestParam(required = false) String schema,
                                          @RequestParam String table,
                                          @RequestParam(required = false) String filters) {
        return tableDataService.exportRows(id, schema, table, filters);
    }

    public record InsertRowReq(String schema, String table, Map<String, String> values) {}

    @PostMapping("/{id}/tables/rows")
    public Map<String, Object> insertRow(@PathVariable Long id, @RequestBody InsertRowReq req) {
        tableDataService.insertRow(id, req.schema(), req.table(), req.values());
        return Map.of("ok", true);
    }

    public record DeleteRowsReq(String schema, String table, List<String> rowIds) {}

    @DeleteMapping("/{id}/tables/rows")
    public Map<String, Object> deleteRows(@PathVariable Long id, @RequestBody DeleteRowsReq req) {
        tableDataService.deleteRows(id, req.schema(), req.table(), req.rowIds());
        return Map.of("ok", true);
    }

    public record ColumnDdlReq(String schema, String table, String action,
                               String column, String newColumn, String type) {}

    /** 字段 Tab：新增/改名/改类型/删除列。 */
    @PostMapping("/{id}/tables/columns")
    public Map<String, Object> columnDdl(@PathVariable Long id, @RequestBody ColumnDdlReq req) {
        tableDataService.columnDdl(id, req.schema(), req.table(), req.action(),
                req.column(), req.newColumn(), req.type());
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
