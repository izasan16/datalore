package com.daaspro.apipub;

import com.daaspro.apipub.entity.ApiAppEntity;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.common.exception.BizException;
import com.daaspro.dataset.entity.DatasetEntity;
import com.daaspro.dataset.mapper.DatasetMapper;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.sqllab.SqlGatewayService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API 发布全链路（服务层）：发布 → 建应用 → 授权 → open 调用。
 * 不用 @Transactional：内置源走独立连接池，租户 schema 每次新建天然隔离。
 */
@SpringBootTest
class ApiPubFlowTest {

    @Autowired ApiPubService pubService;
    @Autowired ApiAppService appService;
    @Autowired OpenApiService openApiService;
    @Autowired SqlGatewayService gateway;
    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;
    @Autowired DatasetMapper datasetMapper;

    /** 建租户 + 内置源上造一张 3 行表 + 注册为数据集。返回 datasetId。 */
    private long setupTenantWithDataset(String tag) {
        String slug = tag + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("API租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin"));
        var source = dataSourceService.create("API-PG-" + slug, "postgresql", "localhost", 15432,
                "daaspro", "public", "daaspro", "daaspro_dev_2026");
        String table = "t_open_" + slug;
        gateway.run(String.valueOf(source.getId()), "CREATE TABLE " + table + "(id bigint, name text, city text)");
        gateway.run(String.valueOf(source.getId()),
                "INSERT INTO " + table + " VALUES (1,'Ann','SH'),(2,'Bob','BJ'),(3,'Cid','SH')");
        DatasetEntity ds = new DatasetEntity();
        ds.setTenantId(tid);
        ds.setName("城市数据" + slug);
        ds.setTableName(table);
        ds.setDatasourceId(source.getId());
        ds.setColumnsDef("[{\"name\":\"id\",\"type\":\"bigint\"},{\"name\":\"name\",\"type\":\"text\"},{\"name\":\"city\",\"type\":\"text\"}]");
        ds.setRowCount(3L);
        ds.setFileName("test.csv");
        datasetMapper.insert(ds);
        return ds.getId();
    }

    private ApiPubEntity publish(long datasetId, String path) {
        return pubService.create(new ApiPubService.CreateReq("城市API", path, datasetId,
                List.of("id", "name", "city"), List.of("city"), 100));
    }

    @Test
    void fullFlowPublishGrantInvoke() {
        long datasetId = setupTenantWithDataset("ap");
        var api = publish(datasetId, "city-" + System.nanoTime() % 100000);
        var app = appService.create("调用方A");

        pubService.setGrants(api.getId(), List.of(app.id()));

        var resolved = openApiService.resolve(api.getPath(), reopen(app));
        var result = openApiService.query(resolved, Map.of("city", "SH"), null, null, "id", "asc");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.columns()).containsExactly("id", "name", "city");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("1", "Ann", "SH");
    }

    @Test
    void unknownParamRejected() {
        long datasetId = setupTenantWithDataset("au");
        var api = publish(datasetId, "unk-" + System.nanoTime() % 100000);
        var app = appService.create("调用方U");
        pubService.setGrants(api.getId(), List.of(app.id()));
        var resolved = openApiService.resolve(api.getPath(), reopen(app));
        assertThatThrownBy(() -> openApiService.query(resolved, Map.of("evil", "1"), null, null, null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(400));
    }

    @Test
    void authAndGrantErrors() {
        long datasetId = setupTenantWithDataset("ae");
        var api = publish(datasetId, "auth-" + System.nanoTime() % 100000);
        var app = appService.create("调用方E");

        // 错 secret → 401
        assertThatThrownBy(() -> openApiService.authenticate(app.appKey(), "sk_wrong"))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(401));
        // 缺头 → 401
        assertThatThrownBy(() -> openApiService.authenticate(null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class);
        // 未授权调用 → 404
        var fresh = reopen(app);
        assertThatThrownBy(() -> openApiService.resolve(api.getPath(), fresh))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(404));

        pubService.setGrants(api.getId(), List.of(app.id()));
        // 停用 → 404
        pubService.update(api.getId(), new ApiPubService.UpdateReq(null, null, null, null, 0));
        assertThatThrownBy(() -> openApiService.resolve(api.getPath(), fresh))
                .isInstanceOf(OpenApiService.OpenApiException.class);
    }

    @Test
    void pathGloballyUniqueAcrossTenants() {
        long datasetA = setupTenantWithDataset("pa");
        String shared = "shared-" + System.nanoTime() % 100000;
        publish(datasetA, shared);

        long datasetB = setupTenantWithDataset("pb");
        assertThatThrownBy(() -> publish(datasetB, shared))
                .isInstanceOf(BizException.class).hasMessageContaining("路径已被占用");
    }

    @Test
    void validationAndCrossTenantGuards() {
        long datasetId = setupTenantWithDataset("av");
        // 非法 path
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "Bad Path!", datasetId, List.of("id"), List.of(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("路径");
        // 过滤列必须是暴露列
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "ok-" + System.nanoTime() % 100000, datasetId, List.of("id"), List.of("city"), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("暴露列");
        // 列不属于数据集
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "ok2-" + System.nanoTime() % 100000, datasetId, List.of("ghost"), List.of(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("不属于");

        var api = publish(datasetId, "guard-" + System.nanoTime() % 100000);

        // 切到另一租户：跨租户不可见
        String slugB = "xb" + (System.nanoTime() % 100000000);
        long b = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("B" + slugB, "admb" + slugB, "password123"));
        TenantContext.set(new TenantContext.Ctx(b, 2L, "admb", "admin"));
        assertThatThrownBy(() -> pubService.getOwned(api.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在");
        // order_by 非暴露列 → 400（用B租户上下文也行，resolve 不查 TenantContext）
        TenantContext.clear();
    }

    @Test
    void sizeAndOrderLimits() {
        long datasetId = setupTenantWithDataset("as");
        var api = pubService.create(new ApiPubService.CreateReq(
                "限流API", "lim-" + System.nanoTime() % 100000, datasetId,
                List.of("id", "name"), List.of(), 2));
        var app = appService.create("调用方L");
        pubService.setGrants(api.getId(), List.of(app.id()));
        var resolved = openApiService.resolve(api.getPath(), reopen(app));
        // size 请求 100 超 max_rows=2 → 钳制到 2
        var r = openApiService.query(resolved, Map.of(), null, "100", null, null);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.rows()).hasSize(2);
        assertThat(r.total()).isEqualTo(3);
        // order_by 非暴露列 → 400
        assertThatThrownBy(() -> openApiService.query(resolved, Map.of(), null, null, "city", "asc"))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(400));
    }

    /** secret 只在 create 返回一次；重新从库里取 app 实体走 reveal 链路 */
    private ApiAppEntity reopen(ApiAppService.Created c) {
        return appService.getOwned(c.id());
    }
}
