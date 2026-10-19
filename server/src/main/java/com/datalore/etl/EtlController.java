package com.datalore.etl;

import com.datalore.etl.entity.EtlTaskEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/etl")
public class EtlController {
    private final EtlService service;

    public EtlController(EtlService service) { this.service = service; }

    public record TaskBody(@NotBlank String name, String description, Long sourceDatasourceId, Long targetDatasourceId,
                           String scheduleType, java.time.LocalDateTime onceAt, String cronExpression,
                           java.time.LocalDateTime scheduleEndAt, Integer maxRetries, Integer retryIntervalSeconds,
                           String inputType, String sourceSchema, String sourceTable, String selectSql,
                           String parametersJson, String targetSchema, String targetTable, String targetTableMode,
                           String writeMode, String fieldMappingsJson, String upsertKeysJson, String createTableDdl) {}

    @GetMapping
    public List<Map<String, Object>> list() { return service.listTasks(); }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody TaskBody body) {
        EtlTaskEntity t = service.createTask(taskReq(body));
        return Map.of("id", t.getId(), "configVersion", t.getConfigVersion());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) { return service.getTaskView(id); }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody TaskBody body) {
        EtlTaskEntity t = service.updateTask(id, taskReq(body));
        return Map.of("id", t.getId(), "configVersion", t.getConfigVersion());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) { service.deleteTask(id); return Map.of("ok", true); }

    @PostMapping("/{id}/copy")
    public Map<String, Object> copy(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Map.of("id", service.copyTask(id, body.get("name")).getId());
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Map.of("status", service.setStatus(id, body.get("status")).getStatus());
    }

    @PostMapping("/ddl/parse")
    public Map<String, Object> parseDdl(@RequestBody Map<String, String> body) {
        return service.parseDdl(body.get("ddl"));
    }

    private EtlService.TaskReq taskReq(TaskBody b) {
        return new EtlService.TaskReq(b.name(), b.description(), b.sourceDatasourceId(), b.targetDatasourceId(),
                b.scheduleType(), b.onceAt(), b.cronExpression(), b.scheduleEndAt(), b.maxRetries(),
                b.retryIntervalSeconds(), b.inputType(), b.sourceSchema(), b.sourceTable(), b.selectSql(),
                b.parametersJson(), b.targetSchema(), b.targetTable(), b.targetTableMode(), b.writeMode(),
                b.fieldMappingsJson(), b.upsertKeysJson(), b.createTableDdl());
    }
}
