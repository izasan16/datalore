package com.datalore.datasource.controller;

import com.datalore.datasource.entity.DataSourceEntity;
import com.datalore.datasource.service.DataSourceService;
import com.datalore.datasource.service.ColumnTypes;
import com.datalore.datasource.service.TableDataService;
import com.datalore.datasource.service.TableDeleteService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
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
    private final TableDeleteService tableDeleteService;

    public DataSourceController(DataSourceService service, TableDataService tableDataService,
                                TableDeleteService tableDeleteService) {
        this.service = service;
        this.tableDataService = tableDataService;
        this.tableDeleteService = tableDeleteService;
    }

    public record CreateReq(@NotBlank String name, @NotBlank String type, @NotBlank String host,
                            Integer port, String databaseName, String schemaName,
                            String username, String password) {}

    public record UpdateReq(String name, String host, Integer port, String databaseName,
                            String schemaName, String username, String password) {}

    public record TestReq(@NotBlank String type, @NotBlank String host, Integer port,
                          String databaseName, String schemaName,
                          String username, String password) {}

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

    public record BatchColumnReq(String schema, String table,
                                 List<TableDataService.ColumnChange> changes, boolean preview) {}

    /** 字段 Tab 整表编辑：批量字段变更，preview=true 返回将执行的语句不执行。 */
    @PostMapping("/{id}/tables/columns/batch")
    public Map<String, Object> batchColumnDdl(@PathVariable Long id, @RequestBody BatchColumnReq req) {
        List<String> statements = tableDataService.batchColumnDdl(id, req.schema(), req.table(),
                req.changes(), req.preview());
        return Map.of("statements", statements);
    }

    /** 按数据源方言下发原生列类型目录（前端类型下拉与参数输入）。 */
    @GetMapping("/column-types/{dsType}")
    public List<Map<String, Object>> columnTypes(@PathVariable String dsType) {
        return ColumnTypes.of(dsType).stream().map(d -> Map.<String, Object>of(
                "base", d.base(), "label", d.label(), "param", d.param().name(),
                "family", d.family().name(), "aliases", d.aliases())).toList();
    }

    /** 删表引用检查：ETL 表输入/目标、API 发布、导入记录。 */
    @GetMapping("/{id}/tables/refs")
    public List<Map<String, Object>> tableRefs(@PathVariable Long id,
                                               @RequestParam(required = false) String schema,
                                               @RequestParam String table) {
        return tableDeleteService.refs(id, schema, table);
    }

    /** 删表：force=false 时有引用返回 409 + 引用明细。 */
    @DeleteMapping("/{id}/tables")
    public ResponseEntity<Map<String, Object>> deleteTable(@PathVariable Long id,
                                                           @RequestParam(required = false) String schema,
                                                           @RequestParam String table,
                                                           @RequestParam(defaultValue = "false") boolean force) {
        var refs = tableDeleteService.refs(id, schema, table);
        if (!refs.isEmpty() && !force) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "该表被以下资源引用，强制删除后这些资源将无法正常工作",
                    "refs", refs));
        }
        tableDeleteService.drop(id, schema, table);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }
}
