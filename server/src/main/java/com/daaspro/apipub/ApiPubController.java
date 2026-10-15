package com.daaspro.apipub;

import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.mapper.DataSourceMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端（登录态）：API 发布配置与调用凭证。 */
@RestController
@RequestMapping("/api")
@Validated
public class ApiPubController {

    private final ApiPubService pubService;
    private final DataSourceMapper dsMapper;

    public ApiPubController(ApiPubService pubService, DataSourceMapper dsMapper) {
        this.pubService = pubService;
        this.dsMapper = dsMapper;
    }

    public record ParamReq(String name, String type, Boolean required, String defaultValue) {}

    public record CreateReq(@NotBlank String name, @NotBlank String path,
                            String sourceType, String sqlText, List<ParamReq> params,
                            Long datasourceId, String schemaName, String tableName,
                            List<String> columns, List<String> filterColumns, Integer maxRows) {}

    public record UpdateReq(String name, String sourceType, String sqlText, List<ParamReq> params,
                            Long datasourceId, String schemaName, String tableName,
                            List<String> columns, List<String> filterColumns, Integer maxRows, Integer status) {}

    @GetMapping("/apipubs")
    public List<Map<String, Object>> listPubs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiPubEntity a : pubService.list()) {
            out.add(toView(a, false));
        }
        return out;
    }

    @GetMapping("/apipubs/{id}")
    public Map<String, Object> getPub(@PathVariable Long id) {
        return toView(pubService.getOwned(id), true);
    }

    @PostMapping("/apipubs")
    public Map<String, Object> createPub(@RequestBody CreateReq req) {
        var a = pubService.create(new ApiPubService.CreateReq(
                req.name(), req.path(), req.sourceType(), req.sqlText(), toParams(req.params()),
                req.datasourceId(), req.schemaName(), req.tableName(),
                req.columns(), req.filterColumns(), req.maxRows()));
        return toView(a, true);
    }

    @PutMapping("/apipubs/{id}")
    public Map<String, Object> updatePub(@PathVariable Long id, @RequestBody UpdateReq req) {
        var a = pubService.update(id, new ApiPubService.UpdateReq(
                req.name(), req.sourceType(), req.sqlText(), toParams(req.params()),
                req.datasourceId(), req.schemaName(), req.tableName(),
                req.columns(), req.filterColumns(), req.maxRows(), req.status()));
        return toView(a, true);
    }

    @DeleteMapping("/apipubs/{id}")
    public Map<String, Object> deletePub(@PathVariable Long id) {
        pubService.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/apipubs/{id}/credential")
    public Map<String, Object> getCredential(@PathVariable Long id) {
        var c = pubService.credential(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiKey", c.apiKey());
        m.put("apiSecret", c.apiSecret());
        m.put("secretSet", c.secretSet());
        return m;
    }

    @PostMapping("/apipubs/{id}/credential/reset")
    public Map<String, Object> resetCredential(@PathVariable Long id) {
        var c = pubService.resetCredential(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiKey", c.apiKey());
        m.put("apiSecret", c.apiSecret());
        m.put("secretSet", c.secretSet());
        return m;
    }

    private List<ApiPubService.ParamDef> toParams(List<ParamReq> reqs) {
        if (reqs == null) return null;
        return reqs.stream()
                .map(r -> new ApiPubService.ParamDef(r.name(), r.type(), r.required(), r.defaultValue()))
                .toList();
    }

    private Map<String, Object> toView(ApiPubEntity a, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("path", a.getPath());
        m.put("sourceType", a.getSourceType() == null ? "TABLE" : a.getSourceType());
        m.put("datasourceId", a.getDatasourceId());
        m.put("schemaName", a.getSchemaName() == null ? "" : a.getSchemaName());
        m.put("tableName", a.getTableName() == null ? "" : a.getTableName());
        DataSourceEntity ds = a.getDatasourceId() == null ? null : dsMapper.selectById(a.getDatasourceId());
        m.put("datasourceName", ds == null ? "(数据源已删除)" : ds.getName());
        m.put("columns", pubService.parseJson(a.getColumns()));
        m.put("filterColumns", pubService.parseJson(a.getFilterColumns()));
        m.put("maxRows", a.getMaxRows());
        m.put("status", a.getStatus());
        m.put("apiKey", a.getApiKey());
        if (detail) {
            m.put("sqlText", a.getSqlText() == null ? "" : a.getSqlText());
            m.put("params", pubService.parseParams(a.getParamsJson()));
        }
        m.put("updatedAt", a.getUpdatedAt() == null ? "" : a.getUpdatedAt().toString());
        return m;
    }
}
