package com.daaspro.apipub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.apipub.entity.ApiAppEntity;
import com.daaspro.apipub.entity.ApiGrantEntity;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.apipub.mapper.ApiAppMapper;
import com.daaspro.apipub.mapper.ApiGrantMapper;
import com.daaspro.apipub.mapper.ApiPubMapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API 发布管理：数据源表 → 只读 REST API。
 * MVP 只支持等值过滤查询；自定义 SQL 发布、字段脱敏、限流为后续迭代。
 */
@Service
public class ApiPubService {

    /** 路径段：小写字母/数字开头，允许 - _，长度 2..64，全局唯一 */
    private static final Pattern PATH = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");

    private final ApiPubMapper mapper;
    private final ApiAppMapper appMapper;
    private final ApiGrantMapper grantMapper;
    private final DataSourceService dataSourceService;
    private final ObjectMapper om = new ObjectMapper();

    public ApiPubService(ApiPubMapper mapper, ApiAppMapper appMapper, ApiGrantMapper grantMapper,
                         DataSourceService dataSourceService) {
        this.mapper = mapper;
        this.appMapper = appMapper;
        this.grantMapper = grantMapper;
        this.dataSourceService = dataSourceService;
    }

    public record CreateReq(String name, String path, Long datasourceId, String schemaName, String tableName,
                            List<String> columns,
                            List<String> filterColumns, Integer maxRows) {}

    public record UpdateReq(String name, List<String> columns, List<String> filterColumns,
                            Integer maxRows, Integer status) {}

    public ApiPubEntity create(CreateReq req) {
        if (req.name() == null || req.name().isBlank()) throw new BizException(400, "API 名称不能为空");
        if (req.path() == null || !PATH.matcher(req.path()).matches()) {
            throw new BizException(400, "路径段不合法：仅小写字母/数字/-/_，2-64 位且以字母数字开头");
        }
        if (req.columns() == null || req.columns().isEmpty()) throw new BizException(400, "至少选择一列对外暴露");
        Long pathDup = mapper.selectCount(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getPath, req.path()));
        if (pathDup > 0) throw new BizException(400, "路径已被占用（全平台唯一），请换一个");

        DataSourceEntity ds = dataSourceService.getOwned(req.datasourceId());
        Set<String> tableCols = liveColumnNames(ds, req.schemaName(), req.tableName());
        for (String col : req.columns()) {
            if (!tableCols.contains(col)) throw new BizException(400, "列不属于该表: " + col);
        }
        List<String> filters = req.filterColumns() == null ? List.of() : req.filterColumns();
        Set<String> exposed = Set.copyOf(req.columns());
        for (String f : filters) {
            if (!exposed.contains(f)) throw new BizException(400, "过滤列必须是暴露列: " + f);
        }
        int maxRows = req.maxRows() == null || req.maxRows() <= 0 ? 1000 : Math.min(req.maxRows(), 10000);

        ApiPubEntity api = new ApiPubEntity();
        api.setTenantId(TenantContext.requireTenantId());
        api.setName(req.name().trim());
        api.setPath(req.path());
        api.setDatasourceId(ds.getId());
        api.setSchemaName(req.schemaName() == null || req.schemaName().isBlank() ? null : req.schemaName().trim());
        api.setTableName(req.tableName().trim());
        api.setColumns(toJson(req.columns()));
        api.setFilterColumns(toJson(filters));
        api.setMaxRows(maxRows);
        api.setStatus(1);
        api.setCreatedAt(LocalDateTime.now());
        api.setUpdatedAt(LocalDateTime.now());
        mapper.insert(api);
        return api;
    }

    public ApiPubEntity update(Long id, UpdateReq req) {
        ApiPubEntity api = getOwned(id);
        DataSourceEntity ds = dataSourceService.getOwned(api.getDatasourceId());
        Set<String> tableCols = liveColumnNames(ds, api.getSchemaName(), api.getTableName());
        if (req.name() != null && !req.name().isBlank()) api.setName(req.name().trim());
        if (req.columns() != null && !req.columns().isEmpty()) {
            for (String col : req.columns()) {
                if (!tableCols.contains(col)) throw new BizException(400, "列不属于该表: " + col);
            }
            api.setColumns(toJson(req.columns()));
        }
        if (req.filterColumns() != null) {
            Set<String> exposed = parseJson(api.getColumns()).stream().collect(Collectors.toSet());
            for (String f : req.filterColumns()) {
                if (!exposed.contains(f)) throw new BizException(400, "过滤列必须是暴露列: " + f);
            }
            api.setFilterColumns(toJson(req.filterColumns()));
        }
        if (req.maxRows() != null && req.maxRows() > 0) api.setMaxRows(Math.min(req.maxRows(), 10000));
        if (req.status() != null) api.setStatus(req.status() == 0 ? 0 : 1);
        api.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(api);
        return api;
    }

    public void delete(Long id) {
        ApiPubEntity api = getOwned(id);
        grantMapper.delete(new LambdaQueryWrapper<ApiGrantEntity>().eq(ApiGrantEntity::getApiId, api.getId()));
        mapper.deleteById(id);
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

    /** 目标表实时列名集合（列结构以数据库为准，不再依赖导入记录）。 */
    public Set<String> liveColumnNames(DataSourceEntity ds, String schema, String table) {
        if (table == null || table.isBlank()) throw new BizException(400, "请选择要发布的表");
        return dataSourceService.listColumns(ds.getId(), table, schema).stream()
                .map(c -> c.get("name")).collect(Collectors.toSet());
    }

    /** 设置某 API 的授权应用集合（全量替换），应用必须属于当前租户 */
    public void setGrants(Long apiId, List<Long> appIds) {
        ApiPubEntity api = getOwned(apiId);
        List<Long> ids = appIds == null ? List.of() : appIds;
        if (!ids.isEmpty()) {
            List<ApiAppEntity> apps = appMapper.selectBatchIds(ids);
            if (apps.size() != ids.stream().distinct().count()
                    || apps.stream().anyMatch(a -> !a.getTenantId().equals(TenantContext.requireTenantId()))) {
                throw new BizException(400, "包含不存在或不属于本租户的应用");
            }
        }
        grantMapper.delete(new LambdaQueryWrapper<ApiGrantEntity>().eq(ApiGrantEntity::getApiId, api.getId()));
        for (Long appId : ids.stream().distinct().toList()) {
            ApiGrantEntity g = new ApiGrantEntity();
            g.setTenantId(api.getTenantId());
            g.setAppId(appId);
            g.setApiId(api.getId());
            g.setCreatedAt(LocalDateTime.now());
            grantMapper.insert(g);
        }
    }

    public List<Long> grantAppIds(Long apiId) {
        ApiPubEntity api = getOwned(apiId);
        return grantMapper.selectList(new LambdaQueryWrapper<ApiGrantEntity>()
                        .eq(ApiGrantEntity::getApiId, api.getId())).stream()
                .map(ApiGrantEntity::getAppId).toList();
    }

    public long grantCount(Long apiId) {
        return grantMapper.selectCount(new LambdaQueryWrapper<ApiGrantEntity>()
                .eq(ApiGrantEntity::getApiId, apiId));
    }

    public List<Map<String, String>> parseColumns(String columnsDef) {
        try {
            return om.readValue(columnsDef, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            throw new BizException(500, "列配置损坏");
        }
    }

    public List<String> parseJson(String json) {
        try {
            return om.readValue(json, new TypeReference<List<String>>() {});
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
}
