package com.daaspro.etl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.etl.entity.EtlRunEntity;
import com.daaspro.etl.entity.EtlTaskEntity;
import com.daaspro.etl.mapper.EtlRunMapper;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EtlRunService {
    private final EtlRunMapper runMapper;
    private final EtlService etlService;
    private final DataSourceService dataSourceService;
    private final AesGcmCipher cipher;
    private final RestClient worker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EtlRunService(EtlRunMapper runMapper, EtlService etlService, DataSourceService dataSourceService,
                         AesGcmCipher cipher, @Value("${daaspro.etl.worker-url:http://localhost:8090}") String workerUrl,
                         @Value("${daaspro.etl.worker-token:dev-worker-token}") String workerToken) {
        this.runMapper = runMapper; this.etlService = etlService; this.dataSourceService = dataSourceService; this.cipher = cipher;
        this.worker = RestClient.builder().baseUrl(workerUrl).defaultHeader("Authorization", "Bearer " + workerToken).build();
    }

    @Transactional
    public EtlRunEntity run(Long taskId, Map<String, Object> parameters) {
        EtlTaskEntity task = etlService.getTask(taskId);
        Long tenant = TenantContext.requireTenantId();
        long active = runMapper.selectCount(new LambdaQueryWrapper<EtlRunEntity>().eq(EtlRunEntity::getTenantId, tenant)
                .eq(EtlRunEntity::getTaskId, taskId).in(EtlRunEntity::getStatus, List.of("QUEUED", "RUNNING")));
        if (active > 0) throw new BizException(400, "该任务已有排队或运行中的实例");
        if (task.getTargetTable() == null) throw new BizException(400, "请先完成映射配置后再运行");
        EtlRunEntity run = new EtlRunEntity(); run.setTenantId(tenant); run.setTaskId(taskId); run.setTriggerType("MANUAL");
        run.setStatus("QUEUED"); run.setCurrentAttempt(1); run.setReadRows(0L); run.setWrittenRows(0L); run.setMappingStatsJson("{}");
        run.setParametersJson(toJson(parameters == null ? Map.of() : parameters)); run.setConfigVersion(task.getConfigVersion()); run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        try {
            String workerId = "run-" + run.getId() + "-" + UUID.randomUUID();
            Map<String, Object> job = new LinkedHashMap<>(); job.put("workerRunId", workerId); job.put("mappings", List.of(buildMapping(task)));
            Map<?, ?> response = worker.post().uri("/internal/runs").contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(job)).retrieve().body(Map.class);
            run.setWorkerRunId(String.valueOf(response.get("workerRunId"))); run.setStatus("RUNNING"); run.setStartedAt(LocalDateTime.now()); runMapper.updateById(run);
        } catch (Exception e) {
            run.setStatus("FAILED"); run.setErrorSummary("Worker 不可用: " + root(e)); run.setFinishedAt(LocalDateTime.now()); runMapper.updateById(run);
            throw new BizException(400, run.getErrorSummary());
        }
        return run;
    }

    public List<EtlRunEntity> list(Long taskId) {
        etlService.getTask(taskId);
        List<EtlRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<EtlRunEntity>()
                .eq(EtlRunEntity::getTenantId, TenantContext.requireTenantId()).eq(EtlRunEntity::getTaskId, taskId)
                .orderByDesc(EtlRunEntity::getId));
        for (EtlRunEntity run : runs) {
            if (List.of("QUEUED", "RUNNING").contains(run.getStatus())) refresh(run.getId());
        }
        return runs.stream().map(run -> runMapper.selectById(run.getId())).toList();
    }

    public EtlRunEntity get(Long id) { EtlRunEntity r = runMapper.selectById(id); if (r == null || !TenantContext.requireTenantId().equals(r.getTenantId())) throw new BizException(404, "运行记录不存在"); return r; }

    public EtlRunEntity refresh(Long id) {
        EtlRunEntity run = get(id); if (run.getWorkerRunId() == null || List.of("SUCCESS", "FAILED", "CANCELED").contains(run.getStatus())) return run;
        try { Map<?, ?> view = worker.get().uri("/internal/runs/{id}", run.getWorkerRunId()).retrieve().body(Map.class); String status = String.valueOf(view.get("status"));
            if (List.of("SUCCESS", "FAILED", "CANCELED").contains(status)) { run.setStatus(status); run.setReadRows(number(view.get("readRows"))); run.setWrittenRows(number(view.get("writtenRows"))); run.setErrorSummary(view.get("error") == null ? null : String.valueOf(view.get("error"))); run.setFinishedAt(LocalDateTime.now()); runMapper.updateById(run); }
        } catch (Exception ignored) { }
        return run;
    }

    public void cancel(Long id) { EtlRunEntity run = get(id); if (run.getWorkerRunId() == null) throw new BizException(400, "运行尚未提交到 Worker"); worker.post().uri("/internal/runs/{id}/cancel", run.getWorkerRunId()).retrieve().toBodilessEntity(); }

    @Transactional
    public EtlRunEntity retry(Long id) {
        EtlRunEntity old = get(id);
        if (!List.of("FAILED", "CANCELED").contains(old.getStatus())) throw new BizException(400, "仅失败或已取消的运行可以重试");
        return run(old.getTaskId(), null);
    }

    private Map<String, Object> buildMapping(EtlTaskEntity task) {
        DataSourceEntity source = dataSourceService.getOwned(task.getSourceDatasourceId());
        DataSourceEntity target = dataSourceService.getOwned(task.getTargetDatasourceId());
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("source", connection(source));
        job.put("target", connection(target));
        job.put("fields", readList(task.getFieldMappingsJson()));
        job.put("writeMode", task.getWriteMode());
        job.put("targetMode", task.getTargetTableMode());
        job.put("upsertKeys", readStrings(task.getUpsertKeysJson()));
        job.put("select", task.getSelectSql());
        job.put("parameters", readMap(task.getParametersJson()));
        job.put("sourceTable", task.getSourceTable());
        job.put("createDdl", task.getCreateTableDdl());
        Map<String, Object> sourceConfig = (Map<String, Object>) job.get("source");
        sourceConfig.put("schema", task.getSourceSchema() == null || task.getSourceSchema().isBlank() ? source.getSchemaName() : task.getSourceSchema());
        sourceConfig.put("table", task.getSourceTable());
        Map<String, Object> targetConfig = (Map<String, Object>) job.get("target");
        targetConfig.put("schema", task.getTargetSchema() == null || task.getTargetSchema().isBlank() ? target.getSchemaName() : task.getTargetSchema());
        targetConfig.put("table", task.getTargetTable());
        return job;
    }
    private Map<String, Object> connection(DataSourceEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", ds.getType());
        m.put("host", ds.getHost()); m.put("port", ds.getPort()); m.put("databaseName", ds.getDatabaseName());
        m.put("username", ds.getUsername()); m.put("password", ds.getPasswordEnc() == null ? "" : cipher.decrypt(ds.getPasswordEnc()));
        return m;
    }
    private List<Map<String, Object>> readList(String json) { try { return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {}); } catch (Exception e) { throw new BizException(400, "字段映射配置损坏"); } }
    private List<String> readStrings(String json) { try { return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {}); } catch (Exception e) { throw new BizException(400, "键字段配置损坏"); } }
    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json.trim())) return Map.of();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { throw new BizException(400, "运行参数配置损坏"); }
    }
    private String toJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new BizException(500, "运行参数序列化失败"); } }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static String root(Exception e) { Throwable t = e; while (t.getCause() != null) t = t.getCause(); return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
}
