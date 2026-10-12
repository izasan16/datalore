package com.daaspro.etl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.etl.entity.EtlMappingEntity;
import com.daaspro.etl.entity.EtlTaskEntity;
import com.daaspro.etl.mapper.EtlMappingMapper;
import com.daaspro.etl.mapper.EtlTaskMapper;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class EtlService {
    private final EtlTaskMapper taskMapper;
    private final EtlMappingMapper mappingMapper;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EtlService(EtlTaskMapper taskMapper, EtlMappingMapper mappingMapper, DataSourceService dataSourceService) {
        this.taskMapper = taskMapper;
        this.mappingMapper = mappingMapper;
        this.dataSourceService = dataSourceService;
    }

    public record TaskReq(String name, String description, Long sourceDatasourceId, Long targetDatasourceId,
                          String scheduleType, LocalDateTime onceAt, String cronExpression,
                          LocalDateTime scheduleEndAt, Integer maxRetries, Integer retryIntervalSeconds) {}
    public record MappingReq(Integer sortOrder, String inputType, String sourceSchema, String sourceTable,
                             String selectSql, String parametersJson, String targetSchema, String targetTable,
                             String targetTableMode, String writeMode, String fieldMappingsJson,
                             String upsertKeysJson) {}

    public List<Map<String, Object>> listTasks() {
        Long tenantId = TenantContext.requireTenantId();
        return taskMapper.selectList(new LambdaQueryWrapper<EtlTaskEntity>()
                        .eq(EtlTaskEntity::getTenantId, tenantId).orderByDesc(EtlTaskEntity::getUpdatedAt))
                .stream().map(t -> taskView(t, mappingCount(t.getId()))).toList();
    }

    public Map<String, Object> getTaskView(Long id) {
        EtlTaskEntity task = getTask(id);
        return taskView(task, mappingCount(id));
    }

    @Transactional
    public EtlTaskEntity createTask(TaskReq req) {
        validateTaskReq(req);
        Long tenantId = TenantContext.requireTenantId();
        if (taskMapper.selectCount(new LambdaQueryWrapper<EtlTaskEntity>()
                .eq(EtlTaskEntity::getTenantId, tenantId).eq(EtlTaskEntity::getName, req.name().trim())) > 0) {
            throw new BizException(400, "同名数据集成任务已存在");
        }
        EtlTaskEntity task = new EtlTaskEntity();
        task.setTenantId(tenantId);
        task.setName(req.name().trim());
        task.setDescription(blankToNull(req.description()));
        task.setSourceDatasourceId(req.sourceDatasourceId());
        task.setTargetDatasourceId(req.targetDatasourceId());
        task.setStatus("DRAFT");
        task.setScheduleType(req.scheduleType().toUpperCase());
        task.setOnceAt(req.onceAt());
        task.setCronExpression(blankToNull(req.cronExpression()));
        task.setScheduleEndAt(req.scheduleEndAt());
        task.setMaxRetries(clamp(req.maxRetries(), 0, 10));
        task.setRetryIntervalSeconds(clamp(req.retryIntervalSeconds(), 5, 86400, 60));
        task.setConfigVersion(1);
        task.setCreatedBy(TenantContext.get().userId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    @Transactional
    public EtlTaskEntity updateTask(Long id, TaskReq req) {
        validateTaskReq(req);
        EtlTaskEntity task = getTask(id);
        Long duplicate = taskMapper.selectCount(new LambdaQueryWrapper<EtlTaskEntity>()
                .eq(EtlTaskEntity::getTenantId, TenantContext.requireTenantId())
                .eq(EtlTaskEntity::getName, req.name().trim()).ne(EtlTaskEntity::getId, id));
        if (duplicate > 0) throw new BizException(400, "同名数据集成任务已存在");
        task.setName(req.name().trim());
        task.setDescription(blankToNull(req.description()));
        task.setSourceDatasourceId(req.sourceDatasourceId());
        task.setTargetDatasourceId(req.targetDatasourceId());
        task.setScheduleType(req.scheduleType().toUpperCase());
        task.setOnceAt(req.onceAt());
        task.setCronExpression(blankToNull(req.cronExpression()));
        task.setScheduleEndAt(req.scheduleEndAt());
        task.setMaxRetries(clamp(req.maxRetries(), 0, 10));
        task.setRetryIntervalSeconds(clamp(req.retryIntervalSeconds(), 5, 86400, 60));
        task.setConfigVersion(task.getConfigVersion() + 1);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void deleteTask(Long id) {
        EtlTaskEntity task = getTask(id);
        if ("ACTIVE".equals(task.getStatus())) throw new BizException(400, "请先暂停任务再删除");
        mappingMapper.delete(new LambdaQueryWrapper<EtlMappingEntity>().eq(EtlMappingEntity::getTaskId, id)
                .eq(EtlMappingEntity::getTenantId, TenantContext.requireTenantId()));
        taskMapper.deleteById(id);
    }

    public EtlTaskEntity setStatus(Long id, String status) {
        EtlTaskEntity task = getTask(id);
        if (!List.of("ACTIVE", "PAUSED").contains(status)) throw new BizException(400, "任务状态不支持");
        if ("ACTIVE".equals(status) && mappingCount(id) == 0) throw new BizException(400, "至少配置一组表映射后才能启用");
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public EtlTaskEntity copyTask(Long id, String name) {
        EtlTaskEntity source = getTask(id);
        TaskReq req = new TaskReq(name, source.getDescription(), source.getSourceDatasourceId(), source.getTargetDatasourceId(),
                source.getScheduleType(), source.getOnceAt(), source.getCronExpression(), source.getScheduleEndAt(),
                source.getMaxRetries(), source.getRetryIntervalSeconds());
        EtlTaskEntity copy = createTask(req);
        for (EtlMappingEntity mapping : mappings(id)) {
            MappingReq mr = new MappingReq(mapping.getSortOrder(), mapping.getInputType(), mapping.getSourceSchema(), mapping.getSourceTable(),
                    mapping.getSelectSql(), mapping.getParametersJson(), mapping.getTargetSchema(), mapping.getTargetTable(),
                    mapping.getTargetTableMode(), mapping.getWriteMode(), mapping.getFieldMappingsJson(), mapping.getUpsertKeysJson());
            createMapping(copy.getId(), mr);
        }
        return copy;
    }

    public List<EtlMappingEntity> listMappings(Long taskId) { getTask(taskId); return mappings(taskId); }

    @Transactional
    public EtlMappingEntity createMapping(Long taskId, MappingReq req) {
        EtlTaskEntity task = getTask(taskId);
        validateMapping(task, req);
        EtlMappingEntity mapping = toEntity(task, req);
        mapping.setTenantId(TenantContext.requireTenantId());
        mapping.setTaskId(taskId);
        mapping.setCreatedAt(LocalDateTime.now());
        mapping.setUpdatedAt(LocalDateTime.now());
        mappingMapper.insert(mapping);
        bumpVersion(task);
        return mapping;
    }

    @Transactional
    public EtlMappingEntity updateMapping(Long id, MappingReq req) {
        EtlMappingEntity old = getMapping(id);
        EtlTaskEntity task = getTask(old.getTaskId());
        validateMapping(task, req);
        EtlMappingEntity mapping = toEntity(task, req);
        mapping.setId(id); mapping.setTenantId(old.getTenantId()); mapping.setTaskId(old.getTaskId());
        mapping.setCreatedAt(old.getCreatedAt()); mapping.setUpdatedAt(LocalDateTime.now());
        mappingMapper.updateById(mapping);
        bumpVersion(task);
        return mapping;
    }

    @Transactional
    public void deleteMapping(Long id) {
        EtlMappingEntity mapping = getMapping(id);
        mappingMapper.deleteById(id);
        EtlTaskEntity task = getTask(mapping.getTaskId());
        bumpVersion(task);
    }

    public EtlTaskEntity getTask(Long id) {
        EtlTaskEntity task = taskMapper.selectById(id);
        if (task == null || !TenantContext.requireTenantId().equals(task.getTenantId())) throw new BizException(404, "数据集成任务不存在");
        return task;
    }

    private EtlMappingEntity getMapping(Long id) {
        EtlMappingEntity mapping = mappingMapper.selectById(id);
        if (mapping == null || !TenantContext.requireTenantId().equals(mapping.getTenantId())) throw new BizException(404, "表映射不存在");
        return mapping;
    }

    private List<EtlMappingEntity> mappings(Long taskId) {
        return mappingMapper.selectList(new LambdaQueryWrapper<EtlMappingEntity>().eq(EtlMappingEntity::getTaskId, taskId)
                .eq(EtlMappingEntity::getTenantId, TenantContext.requireTenantId()).orderByAsc(EtlMappingEntity::getSortOrder));
    }

    private long mappingCount(Long taskId) { return mappingMapper.selectCount(new LambdaQueryWrapper<EtlMappingEntity>()
            .eq(EtlMappingEntity::getTaskId, taskId).eq(EtlMappingEntity::getTenantId, TenantContext.requireTenantId())); }

    private void validateTaskReq(TaskReq req) {
        if (req == null || req.name() == null || req.name().isBlank()) throw new BizException(400, "任务名称不能为空");
        if (req.sourceDatasourceId() == null || req.targetDatasourceId() == null) throw new BizException(400, "必须选择源和目标数据源");
        dataSourceService.getOwned(req.sourceDatasourceId()); dataSourceService.getOwned(req.targetDatasourceId());
        if (req.scheduleType() == null || !List.of("MANUAL", "ONCE", "CRON").contains(req.scheduleType().toUpperCase())) throw new BizException(400, "调度方式不支持");
        if ("ONCE".equalsIgnoreCase(req.scheduleType()) && req.onceAt() == null) throw new BizException(400, "单次调度需要执行时间");
        if ("CRON".equalsIgnoreCase(req.scheduleType()) && (req.cronExpression() == null || req.cronExpression().isBlank())) throw new BizException(400, "Cron 调度需要表达式");
    }

    private void validateMapping(EtlTaskEntity task, MappingReq req) {
        if (req == null || req.targetTable() == null || req.targetTable().isBlank()) throw new BizException(400, "目标表不能为空");
        if (!List.of("TABLE", "SELECT").contains(req.inputType())) throw new BizException(400, "输入类型不支持");
        if ("TABLE".equals(req.inputType()) && (req.sourceTable() == null || req.sourceTable().isBlank())) throw new BizException(400, "源表不能为空");
        if ("SELECT".equals(req.inputType())) EtlValidation.validateSelect(req.selectSql());
        List<EtlValidation.FieldMapping> fields = readFields(req.fieldMappingsJson());
        List<String> keys = readStrings(req.upsertKeysJson());
        EtlValidation.validateFieldMappings(fields, keys, req.writeMode());
        EtlValidation.rejectSameTable(task.getSourceDatasourceId(), req.sourceSchema(), req.sourceTable(),
                task.getTargetDatasourceId(), req.targetSchema(), req.targetTable());
    }

    private EtlMappingEntity toEntity(EtlTaskEntity task, MappingReq req) {
        EtlMappingEntity m = new EtlMappingEntity();
        m.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder()); m.setInputType(req.inputType());
        m.setSourceSchema(blankToNull(req.sourceSchema())); m.setSourceTable(blankToNull(req.sourceTable())); m.setSelectSql(blankToNull(req.selectSql()));
        m.setParametersJson(defaultJson(req.parametersJson(), "{}")); m.setTargetSchema(blankToNull(req.targetSchema())); m.setTargetTable(req.targetTable().trim());
        m.setTargetTableMode(req.targetTableMode() == null ? "CREATE" : req.targetTableMode()); m.setWriteMode(req.writeMode());
        m.setFieldMappingsJson(defaultJson(req.fieldMappingsJson(), "[]")); m.setUpsertKeysJson(defaultJson(req.upsertKeysJson(), "[]"));
        return m;
    }

    private List<EtlValidation.FieldMapping> readFields(String json) {
        try { return objectMapper.readValue(defaultJson(json, "[]"), new TypeReference<>() {}); }
        catch (Exception e) { throw new BizException(400, "字段映射 JSON 非法"); }
    }
    private List<String> readStrings(String json) {
        try { return objectMapper.readValue(defaultJson(json, "[]"), new TypeReference<>() {}); }
        catch (Exception e) { throw new BizException(400, "键字段 JSON 非法"); }
    }
    private Map<String, Object> taskView(EtlTaskEntity t, long count) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("name", t.getName());
        view.put("description", t.getDescription() == null ? "" : t.getDescription());
        view.put("sourceDatasourceId", t.getSourceDatasourceId());
        view.put("targetDatasourceId", t.getTargetDatasourceId());
        view.put("status", t.getStatus());
        view.put("scheduleType", t.getScheduleType());
        view.put("onceAt", t.getOnceAt() == null ? "" : t.getOnceAt().toString());
        view.put("cronExpression", t.getCronExpression() == null ? "" : t.getCronExpression());
        view.put("configVersion", t.getConfigVersion());
        view.put("mappingCount", count);
        view.put("updatedAt", t.getUpdatedAt() == null ? "" : t.getUpdatedAt().toString());
        return view;
    }
    private void bumpVersion(EtlTaskEntity task) { task.setConfigVersion(task.getConfigVersion() + 1); task.setUpdatedAt(LocalDateTime.now()); taskMapper.updateById(task); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String defaultJson(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static int clamp(Integer v, int min, int max) { return clamp(v, min, max, min); }
    private static int clamp(Integer v, int min, int max, int def) { return v == null ? def : Math.max(min, Math.min(max, v)); }
}
