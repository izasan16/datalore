package com.daaspro.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.apipub.mapper.ApiPubMapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.etl.entity.EtlRunEntity;
import com.daaspro.etl.entity.EtlTaskEntity;
import com.daaspro.etl.mapper.EtlRunMapper;
import com.daaspro.etl.mapper.EtlTaskMapper;
import com.daaspro.imported.entity.ImportedTableEntity;
import com.daaspro.imported.mapper.ImportedTableMapper;
import com.daaspro.tenant.context.TenantContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据管理统一删表：所有表可删，删除前检查结构化引用（ETL 表输入/目标、API 发布、导入记录），
 * 有引用时由调用方决定是否 force。强删只 DROP 物理表并清理导入记录，不做其他级联。
 */
@Service
public class TableDeleteService {

    private final DataSourceService dataSourceService;
    private final ConnectionManager connectionManager;
    private final AesGcmCipher cipher;
    private final ApiPubMapper apiPubMapper;
    private final EtlTaskMapper etlTaskMapper;
    private final EtlRunMapper etlRunMapper;
    private final ImportedTableMapper importedTableMapper;

    public TableDeleteService(DataSourceService dataSourceService, ConnectionManager connectionManager,
                              AesGcmCipher cipher, ApiPubMapper apiPubMapper, EtlTaskMapper etlTaskMapper,
                              EtlRunMapper etlRunMapper, ImportedTableMapper importedTableMapper) {
        this.dataSourceService = dataSourceService;
        this.connectionManager = connectionManager;
        this.cipher = cipher;
        this.apiPubMapper = apiPubMapper;
        this.etlTaskMapper = etlTaskMapper;
        this.etlRunMapper = etlRunMapper;
        this.importedTableMapper = importedTableMapper;
    }

    /** 结构化引用明细。SELECT 输入的 ETL 不做内文解析，不纳入检查。 */
    public List<Map<String, Object>> refs(Long dsId, String schemaParam, String table) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        Long tenantId = TenantContext.requireTenantId();
        List<Map<String, Object>> out = new ArrayList<>();

        List<EtlTaskEntity> etlTasks = etlTaskMapper.selectList(new LambdaQueryWrapper<EtlTaskEntity>()
                .eq(EtlTaskEntity::getTenantId, tenantId));
        for (EtlTaskEntity t : etlTasks) {
            boolean sourceHit = "TABLE".equals(t.getInputType())
                    && Objects.equals(t.getSourceDatasourceId(), dsId)
                    && eqSchema(t.getSourceSchema(), schema)
                    && eqName(t.getSourceTable(), table);
            boolean targetHit = Objects.equals(t.getTargetDatasourceId(), dsId)
                    && eqSchema(t.getTargetSchema(), schema)
                    && eqName(t.getTargetTable(), table);
            if (sourceHit || targetHit) {
                boolean running = etlRunMapper.selectCount(new LambdaQueryWrapper<EtlRunEntity>()
                        .eq(EtlRunEntity::getTaskId, t.getId())
                        .in(EtlRunEntity::getStatus, List.of("QUEUED", "RUNNING"))) > 0;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", sourceHit ? "etl_source" : "etl_target");
                ref.put("id", t.getId());
                ref.put("name", t.getName());
                ref.put("running", running);
                out.add(ref);
            }
        }

        for (ApiPubEntity a : apiPubMapper.selectList(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getTenantId, tenantId))) {
            if (Objects.equals(a.getDatasourceId(), dsId)
                    && eqSchema(a.getSchemaName(), schema)
                    && eqName(a.getTableName(), table)) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", "api_pub");
                ref.put("id", a.getId());
                ref.put("name", a.getName());
                ref.put("enabled", a.getStatus() != null && a.getStatus() == 1);
                out.add(ref);
            }
        }

        for (ImportedTableEntity r : importedTableMapper.selectList(new LambdaQueryWrapper<ImportedTableEntity>()
                .eq(ImportedTableEntity::getTenantId, tenantId))) {
            if (Objects.equals(r.getDatasourceId(), dsId)
                    && eqSchema(r.getSchemaName(), schema)
                    && eqName(r.getTableName(), table)) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", "imported");
                ref.put("id", r.getId());
                ref.put("name", r.getName());
                out.add(ref);
            }
        }
        return out;
    }

    /** 删除物理表；同时清理该表的导入记录（如有）。 */
    public void drop(Long dsId, String schemaParam, String table) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        String q = quoteIdent(ds, schema) + "." + quoteIdent(ds, table);
        try (Connection conn = open(ds);
             var st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + q);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "删除失败: " + DataSourceService.rootMessage(e));
        }
        Long tenantId = TenantContext.requireTenantId();
        for (ImportedTableEntity r : importedTableMapper.selectList(new LambdaQueryWrapper<ImportedTableEntity>()
                .eq(ImportedTableEntity::getTenantId, tenantId))) {
            if (Objects.equals(r.getDatasourceId(), dsId)
                    && eqSchema(r.getSchemaName(), schema)
                    && eqName(r.getTableName(), table)) {
                importedTableMapper.deleteById(r.getId());
            }
        }
    }

    private boolean eqSchema(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b == null ? "" : b);
    }

    private boolean eqName(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private String schema(DataSourceEntity ds, String schemaParam) {
        if (schemaParam != null && !schemaParam.isBlank()) return schemaParam.trim();
        return switch (ds.getType()) {
            case "postgresql" -> ds.getSchemaName() == null ? "public" : ds.getSchemaName();
            case "mysql" -> ds.getDatabaseName() == null ? "" : ds.getDatabaseName();
            case "sqlserver" -> "dbo";
            case "oracle" -> ds.getUsername() == null ? "" : ds.getUsername().toUpperCase();
            default -> "public";
        };
    }

    private void validateIdent(String value, String label) {
        if (value == null || !value.matches("^[A-Za-z_][A-Za-z0-9_$]*$")) {
            throw new BizException(400, label + "非法: " + value);
        }
    }

    private String quoteIdent(DataSourceEntity ds, String ident) {
        if ("mysql".equals(ds.getType())) return "`" + ident.replace("`", "``") + "`";
        if ("sqlserver".equals(ds.getType())) return "[" + ident.replace("]", "]]") + "]";
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private Connection open(DataSourceEntity ds) {
        try {
            return connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc())).getConnection();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "获取连接失败: " + DataSourceService.rootMessage(e));
        }
    }
}
