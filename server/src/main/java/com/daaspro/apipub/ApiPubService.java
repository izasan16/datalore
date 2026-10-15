package com.daaspro.apipub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.apipub.mapper.ApiPubMapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.etl.EtlValidation;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API 发布管理：数据表或手写只读 SELECT 发布为 REST API。
 * 每个 API 持有独立 Key/Secret 凭证（secret AES-GCM 加密存储）。
 */
@Service
public class ApiPubService {

    /** 路径段：小写字母/数字开头，允许 - _，长度 2..64，全局唯一 */
    private static final Pattern PATH = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SOURCE_TYPES = Set.of("TABLE", "SQL");
    private static final Set<String> PARAM_TYPES = Set.of("string", "number");

    private final ApiPubMapper mapper;
    private final DataSourceService dataSourceService;
    private final AesGcmCipher cipher;
    private final ObjectMapper om = new ObjectMapper();

    public ApiPubService(ApiPubMapper mapper, DataSourceService dataSourceService, AesGcmCipher cipher) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
        this.cipher = cipher;
    }

    public record ParamDef(String name, String type, Boolean required, String defaultValue) {}

    public record CreateReq(String name, String path, String sourceType, String sqlText, List<ParamDef> params,
                            Long datasourceId, String schemaName, String tableName,
                            List<String> columns, List<String> filterColumns, Integer maxRows) {}

    public record UpdateReq(String name, String sourceType, String sqlText, List<ParamDef> params,
                            Long datasourceId, String schemaName, String tableName,
                            List<String> columns, List<String> filterColumns, Integer maxRows, Integer status) {}

    public record Credential(String apiKey, String apiSecret, boolean secretSet) {}

    public ApiPubEntity create(CreateReq req) {
        if (req.name() == null || req.name().isBlank()) throw new BizException(400, "API 名称不能为空");
        if (req.path() == null || !PATH.matcher(req.path()).matches()) {
            throw new BizException(400, "路径段不合法：仅小写字母/数字/-/_，2-64 位且以字母数字开头");
        }
        String sourceType = req.sourceType() == null ? "TABLE" : req.sourceType();
        if (!SOURCE_TYPES.contains(sourceType)) throw new BizException(400, "不支持的数据来源类型");
        Long pathDup = mapper.selectCount(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getPath, req.path()));
        if (pathDup > 0) throw new BizException(400, "路径已被占用（全平台唯一），请换一个");

        DataSourceEntity ds = dataSourceService.getOwned(req.datasourceId());
        ApiPubEntity api = new ApiPubEntity();
        api.setTenantId(TenantContext.requireTenantId());
        api.setName(req.name().trim());
        api.setPath(req.path());
        api.setSourceType(sourceType);
        api.setDatasourceId(ds.getId());
        api.setMaxRows(req.maxRows() == null || req.maxRows() <= 0 ? 1000 : Math.min(req.maxRows(), 10000));
        api.setStatus(1);
        api.setCreatedAt(LocalDateTime.now());
        api.setUpdatedAt(LocalDateTime.now());

        if ("SQL".equals(sourceType)) {
            applySqlConfig(api, req.sqlText(), req.params());
            api.setTableName(null);
            api.setColumns("[]");
            api.setFilterColumns("[]");
        } else {
            if (req.columns() == null || req.columns().isEmpty()) throw new BizException(400, "至少选择一列对外暴露");
            Set<String> tableCols = liveColumnNames(ds, req.schemaName(), req.tableName());
            for (String col : req.columns()) {
                if (!tableCols.contains(col)) throw new BizException(400, "列不属于该表: " + col);
            }
            List<String> filters = req.filterColumns() == null ? List.of() : req.filterColumns();
            Set<String> exposed = Set.copyOf(req.columns());
            for (String f : filters) {
                if (!exposed.contains(f)) throw new BizException(400, "过滤列必须是暴露列: " + f);
            }
            api.setSchemaName(req.schemaName() == null || req.schemaName().isBlank() ? null : req.schemaName().trim());
            api.setTableName(req.tableName().trim());
            api.setColumns(toJson(req.columns()));
            api.setFilterColumns(toJson(filters));
            api.setSqlText(null);
            api.setParamsJson("[]");
        }
        applyNewCredential(api);
        mapper.insert(api);
        return api;
    }

    public ApiPubEntity update(Long id, UpdateReq req) {
        ApiPubEntity api = getOwned(id);
        if (req.name() != null && !req.name().isBlank()) api.setName(req.name().trim());
        String sourceType = req.sourceType() == null ? api.getSourceType() : req.sourceType();
        if (!SOURCE_TYPES.contains(sourceType)) throw new BizException(400, "不支持的数据来源类型");
        boolean switched = !sourceType.equals(api.getSourceType());
        api.setSourceType(sourceType);
        api.setUpdatedAt(LocalDateTime.now());

        if ("SQL".equals(sourceType)) {
            if (switched || req.sqlText() != null || req.params() != null) {
                applySqlConfig(api, req.sqlText() != null ? req.sqlText() : api.getSqlText(),
                        req.params() != null ? req.params() : parseParams(api.getParamsJson()));
            }
            if (switched) {
                api.setTableName(null);
                api.setColumns("[]");
                api.setFilterColumns("[]");
            }
        } else {
            if (switched) {
                if (req.tableName() == null || req.tableName().isBlank()) throw new BizException(400, "请选择要发布的表");
                api.setSqlText(null);
                api.setParamsJson("[]");
                api.setSchemaName(req.schemaName() == null || req.schemaName().isBlank() ? null : req.schemaName().trim());
                api.setTableName(req.tableName().trim());
            }
            if (req.datasourceId() != null && !req.datasourceId().equals(api.getDatasourceId())) {
                dataSourceService.getOwned(req.datasourceId());
                api.setDatasourceId(req.datasourceId());
            }
            DataSourceEntity ds = dataSourceService.getOwned(api.getDatasourceId());
            Set<String> tableCols = liveColumnNames(ds, api.getSchemaName(), api.getTableName());
            if (req.columns() != null && !req.columns().isEmpty()) {
                for (String col : req.columns()) {
                    if (!tableCols.contains(col)) throw new BizException(400, "列不属于该表: " + col);
                }
                api.setColumns(toJson(req.columns()));
            } else if (switched) {
                throw new BizException(400, "至少选择一列对外暴露");
            }
            if (req.filterColumns() != null) {
                Set<String> exposed = parseJson(api.getColumns()).stream().collect(Collectors.toSet());
                for (String f : req.filterColumns()) {
                    if (!exposed.contains(f)) throw new BizException(400, "过滤列必须是暴露列: " + f);
                }
                api.setFilterColumns(toJson(req.filterColumns()));
            }
        }
        if (req.maxRows() != null && req.maxRows() > 0) api.setMaxRows(Math.min(req.maxRows(), 10000));
        if (req.status() != null) api.setStatus(req.status() == 0 ? 0 : 1);
        mapper.updateById(api);
        return api;
    }

    public void delete(Long id) {
        ApiPubEntity api = getOwned(id);
        mapper.deleteById(api.getId());
    }

    public List<ApiPubEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(ApiPubEntity::getId));
    }

    public ApiPubEntity getOwned(Long id) {
        ApiPubEntity api = mapper.selectById(id);
        if (api == null || !api.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "API 不存在");
        }
        return api;
    }

    /** 查看（揭示）调用凭证；secret 尚未生成时返回 secretSet=false。 */
    public Credential credential(Long id) {
        ApiPubEntity api = getOwned(id);
        boolean set = api.getApiSecretEnc() != null && !api.getApiSecretEnc().isBlank();
        try {
            return new Credential(api.getApiKey(), set ? cipher.decrypt(api.getApiSecretEnc()) : null, set);
        } catch (Exception e) {
            throw new BizException(500, "凭证解密失败");
        }
    }

    /** 重置调用凭证：生成新 Key/Secret，旧 Secret 立即失效。 */
    public Credential resetCredential(Long id) {
        ApiPubEntity api = getOwned(id);
        String secret = applyNewCredential(api);
        api.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(api);
        return new Credential(api.getApiKey(), secret, true);
    }

    /** 目标表实时列名集合（列结构以数据库为准）。 */
    public Set<String> liveColumnNames(DataSourceEntity ds, String schema, String table) {
        if (table == null || table.isBlank()) throw new BizException(400, "请选择要发布的表");
        return dataSourceService.listColumns(ds.getId(), table, schema).stream()
                .map(c -> c.get("name")).collect(Collectors.toSet());
    }

    /** SQL 模式配置：只读校验 + 声明参数与 SQL 提取参数完全一致。 */
    private void applySqlConfig(ApiPubEntity api, String sqlText, List<ParamDef> params) {
        EtlValidation.SelectValidation v = EtlValidation.validateSelect(sqlText);
        List<ParamDef> defs = params == null ? List.of() : params;
        Set<String> declared = new LinkedHashSet<>();
        for (ParamDef p : defs) {
            if (p.name() == null || p.name().isBlank()) throw new BizException(400, "参数名不能为空");
            if (!declared.add(p.name())) throw new BizException(400, "参数重复: " + p.name());
            String type = p.type() == null ? "string" : p.type();
            if (!PARAM_TYPES.contains(type)) throw new BizException(400, "参数类型仅支持 string/number: " + p.name());
        }
        for (String extracted : v.parameters()) {
            if (!declared.contains(extracted)) {
                throw new BizException(400, "SQL 中存在未声明的参数: :" + extracted);
            }
        }
        for (String d : declared) {
            if (!v.parameters().contains(d)) {
                throw new BizException(400, "声明的参数未在 SQL 中使用: :" + d);
            }
        }
        api.setSqlText(sqlText.trim());
        api.setParamsJson(toParamJson(defs));
    }

    private String applyNewCredential(ApiPubEntity api) {
        String secret = random("sk_", 32);
        api.setApiKey(random("ak_", 16));
        api.setApiSecretEnc(cipher.encrypt(secret));
        return secret;
    }

    private String random(String prefix, int len) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public List<ParamDef> parseParams(String json) {
        try {
            return om.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<ParamDef>>() {});
        } catch (Exception e) {
            throw new BizException(500, "参数配置损坏");
        }
    }

    public List<Map<String, String>> parseColumns(String columnsDef) {
        try {
            return om.readValue(columnsDef == null || columnsDef.isBlank() ? "[]" : columnsDef,
                    new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            throw new BizException(500, "列配置损坏");
        }
    }

    public List<String> parseJson(String json) {
        try {
            return om.readValue(json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new BizException(500, "配置损坏");
        }
    }

    private String toJson(List<String> list) {
        try {
            return om.writeValueAsString(list);
        } catch (Exception e) {
            throw new BizException(500, "序列化失败");
        }
    }

    private String toParamJson(List<ParamDef> defs) {
        try {
            return om.writeValueAsString(defs);
        } catch (Exception e) {
            throw new BizException(500, "序列化失败");
        }
    }
}
