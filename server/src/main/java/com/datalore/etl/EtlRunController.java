package com.datalore.etl;

import com.datalore.etl.entity.EtlRunEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/etl")
public class EtlRunController {
    private final EtlRunService service;
    public EtlRunController(EtlRunService service) { this.service = service; }
    @PostMapping("/{taskId}/runs") public EtlRunEntity run(@PathVariable Long taskId, @RequestBody(required = false) Map<String, Object> body) { return service.run(taskId, body); }
    @PostMapping("/runs/{id}/retry") public EtlRunEntity retry(@PathVariable Long id) { return service.retry(id); }
    @GetMapping("/{taskId}/runs") public List<EtlRunEntity> list(@PathVariable Long taskId) { return service.list(taskId); }
    @GetMapping("/runs/{id}") public EtlRunEntity get(@PathVariable Long id) { return service.refresh(id); }
    @PostMapping("/runs/{id}/cancel") public Map<String, Object> cancel(@PathVariable Long id) { service.cancel(id); return Map.of("ok", true); }
}
