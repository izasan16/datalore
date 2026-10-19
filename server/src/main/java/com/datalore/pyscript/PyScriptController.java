package com.datalore.pyscript;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pyscript")
@Validated
public class PyScriptController {

    private final PyScriptService service;
    private final HdfsBrowserService hdfsService;

    public PyScriptController(PyScriptService service, HdfsBrowserService hdfsService) {
        this.service = service;
        this.hdfsService = hdfsService;
    }

    public record CreateReq(@NotBlank String name, Long datasourceId, String content) {}
    public record UpdateReq(String name, Long datasourceId, String content) {}
    public record RunReq(java.util.Map<String, String> params) {}

    @GetMapping
    public List<PyScriptService.ScriptInfo> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PyScriptService.ScriptDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public PyScriptService.ScriptDetail create(@RequestBody CreateReq req) {
        return service.create(new PyScriptService.CreateReq(req.name(), req.datasourceId(), req.content()));
    }

    @PutMapping("/{id}")
    public PyScriptService.ScriptDetail update(@PathVariable Long id, @RequestBody UpdateReq req) {
        return service.update(id, new PyScriptService.UpdateReq(req.name(), req.datasourceId(), req.content()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/run")
    public PyScriptService.RunResult run(@PathVariable Long id, @RequestBody(required = false) RunReq req) {
        return service.run(id, req != null ? new PyScriptService.RunReq(req.params()) : new PyScriptService.RunReq(null));
    }

    @GetMapping("/runs/{runId}/refresh")
    public PyScriptService.RunStatus refresh(@PathVariable Long runId) {
        return service.refresh(runId);
    }

    @GetMapping("/runs/{runId}/logs")
    public Map<String, Object> logs(@PathVariable Long runId, @RequestParam(defaultValue = "0") int offset) {
        String logs = service.logs(runId, offset);
        return Map.of("logs", logs, "offset", offset + logs.length());
    }

    @GetMapping("/{id}/history")
    public List<PyScriptService.RunHistoryItem> history(@PathVariable Long id) {
        return service.history(id);
    }

    @GetMapping("/hdfs/{datasourceId}")
    public List<HdfsBrowserService.HdfsEntry> hdfs(@PathVariable Long datasourceId,
                                                    @RequestParam(required = false, defaultValue = "/") String path) {
        return hdfsService.list(datasourceId, path);
    }
}
