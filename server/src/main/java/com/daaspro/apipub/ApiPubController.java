package com.daaspro.apipub;

import com.daaspro.apipub.entity.ApiAppEntity;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.dataset.entity.DatasetEntity;
import com.daaspro.dataset.mapper.DatasetMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端（登录态）：应用与 API 发布配置、授权。 */
@RestController
@RequestMapping("/api")
@Validated
public class ApiPubController {

    private final ApiAppService appService;
    private final ApiPubService pubService;
    private final DatasetMapper datasetMapper;

    public ApiPubController(ApiAppService appService, ApiPubService pubService, DatasetMapper datasetMapper) {
        this.appService = appService;
        this.pubService = pubService;
        this.datasetMapper = datasetMapper;
    }

    // ---------- 应用 ----------

    public record AppCreateReq(@NotBlank String name) {}

    public record AppRenameReq(@NotBlank String name) {}

    @GetMapping("/apiapps")
    public List<Map<String, Object>> listApps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiAppEntity a : appService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("appKey", a.getAppKey());
            m.put("secretMask", ApiAppService.maskSecret(appService.revealSecret(a.getId())));
            m.put("createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/apiapps")
    public Map<String, Object> createApp(@RequestBody AppCreateReq req) {
        var c = appService.create(req.name());
        return Map.of("id", c.id(), "name", c.name(), "appKey", c.appKey(), "appSecret", c.appSecret());
    }

    @GetMapping("/apiapps/{id}/secret")
    public Map<String, Object> revealSecret(@PathVariable Long id) {
        return Map.of("appSecret", appService.revealSecret(id));
    }

    @PutMapping("/apiapps/{id}")
    public Map<String, Object> renameApp(@PathVariable Long id, @RequestBody AppRenameReq req) {
        appService.rename(id, req.name());
        return Map.of("ok", true);
    }

    @DeleteMapping("/apiapps/{id}")
    public Map<String, Object> deleteApp(@PathVariable Long id) {
        appService.delete(id);
        return Map.of("ok", true);
    }

    // ---------- 发布 ----------

    public record CreateReq(@NotBlank String name, @NotBlank String path, Long datasetId,
                            List<String> columns, List<String> filterColumns, Integer maxRows) {}

    public record UpdateReq(String name, List<String> columns, List<String> filterColumns,
                            Integer maxRows, Integer status) {}

    public record GrantsReq(List<Long> appIds) {}

    @GetMapping("/apipubs")
    public List<Map<String, Object>> listPubs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiPubEntity a : pubService.list()) {
            out.add(toView(a));
        }
        return out;
    }

    @GetMapping("/apipubs/{id}")
    public Map<String, Object> getPub(@PathVariable Long id) {
        return toView(pubService.getOwned(id));
    }

    @PostMapping("/apipubs")
    public Map<String, Object> createPub(@RequestBody CreateReq req) {
        var a = pubService.create(new ApiPubService.CreateReq(
                req.name(), req.path(), req.datasetId(), req.columns(), req.filterColumns(), req.maxRows()));
        return toView(a);
    }

    @PutMapping("/apipubs/{id}")
    public Map<String, Object> updatePub(@PathVariable Long id, @RequestBody UpdateReq req) {
        var a = pubService.update(id, new ApiPubService.UpdateReq(
                req.name(), req.columns(), req.filterColumns(), req.maxRows(), req.status()));
        return toView(a);
    }

    @DeleteMapping("/apipubs/{id}")
    public Map<String, Object> deletePub(@PathVariable Long id) {
        pubService.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/apipubs/{id}/grants")
    public Map<String, Object> grants(@PathVariable Long id) {
        return Map.of("appIds", pubService.grantAppIds(id));
    }

    @PutMapping("/apipubs/{id}/grants")
    public Map<String, Object> setGrants(@PathVariable Long id, @RequestBody GrantsReq req) {
        pubService.setGrants(id, req.appIds());
        return Map.of("ok", true);
    }

    /** 数据集列（供发布表单选择暴露列/过滤列） */
    @GetMapping("/apipubs/dataset-columns")
    public List<Map<String, String>> datasetColumns(@RequestParam Long datasetId) {
        DatasetEntity d = pubService.getDatasetOwned(datasetId);
        return pubService.parseColumns(d.getColumnsDef());
    }

    private Map<String, Object> toView(ApiPubEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("path", a.getPath());
        m.put("datasetId", a.getDatasetId());
        DatasetEntity d = datasetMapper.selectById(a.getDatasetId());
        m.put("datasetName", d == null ? "(数据集已删除)" : d.getName());
        m.put("columns", pubService.parseJson(a.getColumns()));
        m.put("filterColumns", pubService.parseJson(a.getFilterColumns()));
        m.put("maxRows", a.getMaxRows());
        m.put("status", a.getStatus());
        m.put("grantCount", pubService.grantCount(a.getId()));
        m.put("updatedAt", a.getUpdatedAt() == null ? "" : a.getUpdatedAt().toString());
        return m;
    }
}
