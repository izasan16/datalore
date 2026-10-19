package com.datalore.etl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datalore.common.exception.BizException;
import com.datalore.datasource.entity.DataSourceEntity;
import com.datalore.datasource.service.DataSourceService;
import com.datalore.etl.entity.EtlTaskEntity;
import com.datalore.etl.entity.EtlRunEntity;
import com.datalore.etl.mapper.EtlTaskMapper;
import com.datalore.etl.mapper.EtlRunMapper;
import com.datalore.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EtlService {
    private final EtlTaskMapper taskMapper;
    private final EtlRunMapper runMapper;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EtlService(EtlTaskMapper taskMapper, EtlRunMapper runMapper, DataSourceService dataSourceService) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.dataSourceService = dataSourceService;
    }

    /** inputType 为空表示本次只保存任务基本信息/数据源，不更新映射。 */
    public record TaskReq(String name, String description, Long sourceDatasourceId, Long targetDatasourceId,
                          String scheduleType, LocalDateTime onceAt, String cronExpression,
                          LocalDateTime scheduleEndAt, Integer maxRetries, Integer retryIntervalSeconds,
                          String inputType, String sourceSchema, String sourceTable, String selectSql,
                          String parametersJson, String targetSchema, String targetTable, String targetTableMode,
                          String writeMode, String fieldMappingsJson, String upsertKeysJson, String createTableDdl) {}

    public List<Map<String, Object>> listTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<EtlTaskEntity>()
                        .eq(EtlTaskEntity::getTenantId, TenantContext.requireTenantId())
                        .orderByDesc(EtlTaskEntity::getUpdatedAt))
                .stream().map(this::taskView).toList();
    }

    public Map<String, Object> getTaskView(Long id) { return taskView(getTask(id)); }

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
        if (req.inputType() != null && !req.inputType().isBlank()) applyMapping(task, req);
        task.setConfigVersion(task.getConfigVersion() + 1);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void deleteTask(Long id) {
        EtlTaskEntity task = getTask(id);
        if ("ACTIVE".equals(task.getStatus())) throw new BizException(400, "请先暂停任务再删除");
        runMapper.delete(new LambdaQueryWrapper<EtlRunEntity>()
                .eq(EtlRunEntity::getTaskId, id));
        taskMapper.deleteById(id);
    }

    public EtlTaskEntity setStatus(Long id, String status) {
        EtlTaskEntity task = getTask(id);
        if (!List.of("ACTIVE", "PAUSED").contains(status)) throw new BizException(400, "任务状态不支持");
        if ("ACTIVE".equals(status) && task.getTargetTable() == null) {
            throw new BizException(400, "请先完成映射配置后才能启用");
        }
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public EtlTaskEntity copyTask(Long id, String name) {
        EtlTaskEntity source = getTask(id);
        EtlTaskEntity copy = createTask(new TaskReq(name, source.getDescription(), source.getSourceDatasourceId(),
                source.getTargetDatasourceId(), source.getScheduleType(), source.getOnceAt(),
                source.getCronExpression(), source.getScheduleEndAt(), source.getMaxRetries(),
                source.getRetryIntervalSeconds(), null, null, null, null, null, null, null, null,
                null, null, null, null));
        copy.setInputType(source.getInputType());
        copy.setSourceSchema(source.getSourceSchema());
        copy.setSourceTable(source.getSourceTable());
        copy.setSelectSql(source.getSelectSql());
        copy.setParametersJson(source.getParametersJson());
        copy.setTargetSchema(source.getTargetSchema());
        copy.setTargetTable(source.getTargetTable());
        copy.setTargetTableMode(source.getTargetTableMode());
        copy.setWriteMode(source.getWriteMode());
        copy.setFieldMappingsJson(source.getFieldMappingsJson());
        copy.setUpsertKeysJson(source.getUpsertKeysJson());
        copy.setCreateTableDdl(source.getCreateTableDdl());
        copy.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(copy);
        return copy;
    }

    public EtlTaskEntity getTask(Long id) {
        EtlTaskEntity task = taskMapper.selectById(id);
        if (task == null || !TenantContext.requireTenantId().equals(task.getTenantId())) {
            throw new BizException(404, "数据集成任务不存在");
        }
        return task;
    }

    public Map<String, Object> parseDdl(String ddl) {
        EtlValidation.ParsedCreateTable parsed = EtlValidation.parseCreateTable(ddl);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schema", parsed.schema());
        view.put("table", parsed.table());
        view.put("columns", parsed.columns());
        return view;
    }

    private void applyMapping(EtlTaskEntity task, TaskReq req) {
        String inputType = req.inputType();
        if (!List.of("TABLE", "SELECT").contains(inputType)) throw new BizException(400, "输入类型不支持");
        String mode = req.targetTableMode() == null ? "CREATE" : req.targetTableMode();
        if (!List.of("CREATE", "EXISTING").contains(mode)) throw new BizException(400, "目标表策略不支持");
        String writeMode = req.writeMode() == null ? "APPEND" : req.writeMode();
        if ("TABLE".equals(inputType) && (req.sourceTable() == null || req.sourceTable().isBlank())) {
            throw new BizException(400, "源表不能为空");
        }
        if ("SELECT".equals(inputType)) EtlValidation.validateSelect(req.selectSql());

        String targetSchema;
        String targetTable;
        String ddl = null;
        if ("CREATE".equals(mode)) {
            EtlValidation.ParsedCreateTable parsed = EtlValidation.parseCreateTable(req.createTableDdl());
            ddl = req.createTableDdl().trim();
            targetTable = parsed.table();
            targetSchema = parsed.schema() != null ? parsed.schema()
                    : defaultSchema(dataSourceService.getOwned(task.getTargetDatasourceId()));
        } else {
            if (req.targetTable() == null || req.targetTable().isBlank()) throw new BizException(400, "目标表不能为空");
            targetTable = req.targetTable().trim();
            targetSchema = blankToNull(req.targetSchema());
        }
        EtlValidation.validateFieldMappings(readFields(req.fieldMappingsJson()), readStrings(req.upsertKeysJson()), writeMode);
        DataSourceEntity targetDs = dataSourceService.getOwned(task.getTargetDatasourceId());
        if ("UPSERT".equals(writeMode) && "hive".equals(targetDs.getType())) {
            throw new BizException(400, "Hive 目标不支持更新插入（UPSERT），请改用覆盖或追加");
        }
        if (ddl != null && "hive".equals(targetDs.getType())) {
            ddl = hiveCreateDdl(targetSchema, targetTable, EtlValidation.parseCreateTable(req.createTableDdl()));
        }
        EtlValidation.rejectSameTable(task.getSourceDatasourceId(), blankToNull(req.sourceSchema()), blankToNull(req.sourceTable()),
                task.getTargetDatasourceId(), targetSchema, targetTable);

        task.setInputType(inputType);
        task.setSourceSchema(blankToNull(req.sourceSchema()));
        task.setSourceTable(blankToNull(req.sourceTable()));
        task.setSelectSql(blankToNull(req.selectSql()));
        task.setParametersJson(defaultJson(req.parametersJson(), "{}"));
        task.setTargetSchema(targetSchema);
        task.setTargetTable(targetTable);
        task.setTargetTableMode(mode);
        task.setWriteMode(writeMode);
        task.setFieldMappingsJson(defaultJson(req.fieldMappingsJson(), "[]"));
        task.setUpsertKeysJson(defaultJson(req.upsertKeysJson(), "[]"));
        task.setCreateTableDdl(ddl);
    }

    private String defaultSchema(DataSourceEntity ds) {
        if ("hive".equals(ds.getType())) {
            return ds.getSchemaName() == null || ds.getSchemaName().isBlank() ? "default" : ds.getSchemaName();
        }
        return ds.getSchemaName() == null || ds.getSchemaName().isBlank() ? "public" : ds.getSchemaName();
    }

    /** Hive 目标自动建表：把用户建表语句重写为 Hive 方言（反引号 + Hive 类型）。 */
    private String hiveCreateDdl(String schema, String table, EtlValidation.ParsedCreateTable parsed) {
        String cols = parsed.columns().stream()
                .map(c -> "`" + c.name().replace("`", "``") + "` " + hiveType(c.platformType()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        String q = (schema == null || schema.isBlank() ? "" : "`" + schema.replace("`", "``") + "`.")
                + "`" + table.replace("`", "``") + "`";
        return "CREATE TABLE " + q + " (" + cols + ")";
    }

    private String hiveType(String platform) {
        return switch (platform == null ? "text" : platform) {
            case "bigint" -> "BIGINT";
            case "numeric" -> "DECIMAL(18,4)";
            case "timestamp" -> "TIMESTAMP";
            case "date" -> "DATE";
            case "boolean" -> "BOOLEAN";
            default -> "STRING";
        };
    }

    private void validateTaskReq(TaskReq req) {
        if (req == null || req.name() == null || req.name().isBlank()) throw new BizException(400, "任务名称不能为空");
        if (req.sourceDatasourceId() == null || req.targetDatasourceId() == null) throw new BizException(400, "必须选择源和目标数据源");
        dataSourceService.getOwned(req.sourceDatasourceId());
        dataSourceService.getOwned(req.targetDatasourceId());
        if (req.scheduleType() == null || !List.of("MANUAL", "ONCE", "CRON").contains(req.scheduleType().toUpperCase())) throw new BizException(400, "调度方式不支持");
        if ("ONCE".equalsIgnoreCase(req.scheduleType()) && req.onceAt() == null) throw new BizException(400, "单次调度需要执行时间");
        if ("CRON".equalsIgnoreCase(req.scheduleType()) && (req.cronExpression() == null || req.cronExpression().isBlank())) throw new BizException(400, "Cron 调度需要表达式");
    }

    private List<EtlValidation.FieldMapping> readFields(String json) {
        try { return objectMapper.readValue(defaultJson(json, "[]"), new TypeReference<>() {}); }
        catch (Exception e) { throw new BizException(400, "字段映射 JSON 非法"); }
    }

    private List<String> readStrings(String json) {
        try { return objectMapper.readValue(defaultJson(json, "[]"), new TypeReference<>() {}); }
        catch (Exception e) { throw new BizException(400, "键字段 JSON 非法"); }
    }

    private Map<String, Object> taskView(EtlTaskEntity t) {
        Map<String, Object> view = new LinkedHashMap<>();
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
        view.put("hasMapping", t.getTargetTable() != null);
        view.put("inputType", t.getInputType());
        view.put("sourceSchema", t.getSourceSchema());
        view.put("sourceTable", t.getSourceTable());
        view.put("selectSql", t.getSelectSql());
        view.put("parametersJson", t.getParametersJson() == null ? "{}" : t.getParametersJson());
        view.put("targetSchema", t.getTargetSchema());
        view.put("targetTable", t.getTargetTable());
        view.put("targetTableMode", t.getTargetTableMode());
        view.put("writeMode", t.getWriteMode());
        view.put("fieldMappingsJson", t.getFieldMappingsJson() == null ? "[]" : t.getFieldMappingsJson());
        view.put("upsertKeysJson", t.getUpsertKeysJson() == null ? "[]" : t.getUpsertKeysJson());
        view.put("createTableDdl", t.getCreateTableDdl());
        view.put("updatedAt", t.getUpdatedAt() == null ? "" : t.getUpdatedAt().toString());
        return view;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String defaultJson(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static int clamp(Integer v, int min, int max) { return clamp(v, min, max, min); }
    private static int clamp(Integer v, int min, int max, int def) { return v == null ? def : Math.max(min, Math.min(max, v)); }
}
