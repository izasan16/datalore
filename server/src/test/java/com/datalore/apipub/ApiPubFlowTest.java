package com.datalore.apipub;

import com.datalore.apipub.entity.ApiPubEntity;
import com.datalore.common.exception.BizException;
import com.datalore.datasource.service.DataSourceService;
import com.datalore.sqllab.SqlGatewayService;
import com.datalore.tenant.context.TenantContext;
import com.datalore.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API 发布全链路（服务层）：发布（表/SQL）→ 凭证 → open 调用。
 * 不用 @Transactional：外部源独立连接池，租户每次新建天然隔离。
 */
@SpringBootTest
class ApiPubFlowTest {

    @Autowired ApiPubService pubService;
    @Autowired OpenApiService openApiService;
    @Autowired SqlGatewayService gateway;
    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;

    /** 建租户 + 外部源上造一张 3 行表。返回数据源与表名。 */
    private record Setup(long datasourceId, String table) {}

    private Setup setupTenantWithTable(String tag) {
        String slug = tag + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("API租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin"));
        var source = dataSourceService.create("API-PG-" + slug, "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        String table = "t_open_" + slug;
        gateway.run(String.valueOf(source.getId()), "CREATE TABLE " + table + "(id bigint, name text, city text)");
        gateway.run(String.valueOf(source.getId()),
                "INSERT INTO " + table + " VALUES (1,'Ann','SH'),(2,'Bob','BJ'),(3,'Cid','SH')");
        return new Setup(source.getId(), table);
    }

    private ApiPubEntity publishTable(Setup s, String path) {
        return pubService.create(new ApiPubService.CreateReq("城市API", path, "TABLE", null, null,
                s.datasourceId(), "public", s.table(),
                List.of("id", "name", "city"), List.of("city"), 100));
    }

    private ApiPubEntity publishSql(Setup s, String path, String sql, List<ApiPubService.ParamDef> params) {
        return pubService.create(new ApiPubService.CreateReq("SQLAPI", path, "SQL", sql, params,
                s.datasourceId(), null, null, null, null, 100));
    }

    @Test
    void fullFlowTableModeInvoke() {
        var setup = setupTenantWithTable("ap");
        var api = publishTable(setup, "city-" + System.nanoTime() % 100000);
        var cred = pubService.credential(api.getId());

        // 错 secret → 401
        assertThatThrownBy(() -> openApiService.authenticate(cred.apiKey(), "sk_wrong"))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(401));
        // 缺头 → 401
        assertThatThrownBy(() -> openApiService.authenticate(null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class);

        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(cred.apiKey(), cred.apiSecret()));
        var result = openApiService.query(resolved, Map.of("city", "SH"), null, null, "id", "asc");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.columns()).containsExactly("id", "name", "city");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("1", "Ann", "SH");
    }

    @Test
    void sqlModeCreateAndInvoke() {
        var setup = setupTenantWithTable("sq");
        String table = setup.table();
        var api = publishSql(setup, "sql-" + System.nanoTime() % 100000,
                "SELECT id, name FROM " + table + " WHERE city = :city",
                List.of(new ApiPubService.ParamDef("city", "string", true, null)));
        var cred = pubService.credential(api.getId());
        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(cred.apiKey(), cred.apiSecret()));

        var r = openApiService.query(resolved, Map.of("city", "SH"), null, null, null, null);
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.columns()).containsExactly("id", "name");
        assertThat(r.rows().get(0)).containsExactly("1", "Ann");

        // 必填参数缺失 → 400
        assertThatThrownBy(() -> openApiService.query(resolved, Map.of(), null, null, null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(400));
        // 非白名单参数 → 400
        assertThatThrownBy(() -> openApiService.query(resolved, Map.of("evil", "1"), null, null, null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(400));
    }

    @Test
    void sqlModeOptionalParamUsesDefault() {
        var setup = setupTenantWithTable("so");
        String table = setup.table();
        var api = publishSql(setup, "opt-" + System.nanoTime() % 100000,
                "SELECT id, name FROM " + table + " WHERE city = :city",
                List.of(new ApiPubService.ParamDef("city", "string", false, "BJ")));
        var cred = pubService.credential(api.getId());
        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(cred.apiKey(), cred.apiSecret()));
        var r = openApiService.query(resolved, Map.of(), null, null, null, null);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.rows().get(0)).containsExactly("2", "Bob");
        // 显式覆盖
        var r2 = openApiService.query(resolved, Map.of("city", "SH"), null, null, null, null);
        assertThat(r2.total()).isEqualTo(2);
    }

    @Test
    void sqlModeValidation() {
        var setup = setupTenantWithTable("sv");
        String table = setup.table();
        String path = "sqlv-" + System.nanoTime() % 100000;
        // 写操作拒绝
        assertThatThrownBy(() -> publishSql(setup, path,
                "DELETE FROM " + table, List.of()))
                .isInstanceOf(BizException.class);
        // SQL 存在未声明参数
        assertThatThrownBy(() -> publishSql(setup, path,
                "SELECT id FROM " + table + " WHERE city = :city", List.of()))
                .isInstanceOf(BizException.class).hasMessageContaining("未声明");
        // 声明参数未在 SQL 中使用
        assertThatThrownBy(() -> publishSql(setup, path,
                "SELECT id FROM " + table + " WHERE city = 'SH'",
                List.of(new ApiPubService.ParamDef("city", "string", true, null))))
                .isInstanceOf(BizException.class).hasMessageContaining("未在 SQL 中使用");
    }

    @Test
    void credentialResetInvalidatesOldSecret() {
        var setup = setupTenantWithTable("cr");
        var api = publishTable(setup, "cred-" + System.nanoTime() % 100000);
        var old = pubService.credential(api.getId());
        assertThat(old.secretSet()).isTrue();

        var fresh = pubService.resetCredential(api.getId());
        assertThat(fresh.apiKey()).isNotEqualTo(old.apiKey());
        // 旧凭证失效 → 401
        assertThatThrownBy(() -> openApiService.authenticate(old.apiKey(), old.apiSecret()))
                .isInstanceOf(OpenApiService.OpenApiException.class);
        // 新凭证可用
        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(fresh.apiKey(), fresh.apiSecret()));
        assertThat(openApiService.query(resolved, Map.of(), null, null, null, null).total()).isEqualTo(3);
    }

    @Test
    void unknownParamRejected() {
        var setup = setupTenantWithTable("au");
        var api = publishTable(setup, "unk-" + System.nanoTime() % 100000);
        var cred = pubService.credential(api.getId());
        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(cred.apiKey(), cred.apiSecret()));
        assertThatThrownBy(() -> openApiService.query(resolved, Map.of("evil", "1"), null, null, null, null))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(400));
    }

    @Test
    void disabledApiReturns404() {
        var setup = setupTenantWithTable("ds");
        var api = publishTable(setup, "dis-" + System.nanoTime() % 100000);
        var cred = pubService.credential(api.getId());
        var authed = openApiService.authenticate(cred.apiKey(), cred.apiSecret());
        pubService.update(api.getId(), new ApiPubService.UpdateReq(null, null, null, null, null, null, null, null, null, null, 0));
        assertThatThrownBy(() -> openApiService.resolve(api.getPath(), authed))
                .isInstanceOf(OpenApiService.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiService.OpenApiException) e).status).isEqualTo(404));
    }

    @Test
    void pathGloballyUniqueAcrossTenants() {
        var setupA = setupTenantWithTable("pa");
        String shared = "shared-" + System.nanoTime() % 100000;
        publishTable(setupA, shared);

        var setupB = setupTenantWithTable("pb");
        assertThatThrownBy(() -> publishTable(setupB, shared))
                .isInstanceOf(BizException.class).hasMessageContaining("路径已被占用");
    }

    @Test
    void validationAndCrossTenantGuards() {
        var setup = setupTenantWithTable("av");
        // 非法 path
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "Bad Path!", "TABLE", null, null, setup.datasourceId(), "public", setup.table(),
                List.of("id"), List.of(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("路径");
        // 过滤列必须是暴露列
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "ok-" + System.nanoTime() % 100000, "TABLE", null, null, setup.datasourceId(), "public", setup.table(),
                List.of("id"), List.of("city"), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("暴露列");
        // 列不属于目标表
        assertThatThrownBy(() -> pubService.create(new ApiPubService.CreateReq(
                "x", "ok2-" + System.nanoTime() % 100000, "TABLE", null, null, setup.datasourceId(), "public", setup.table(),
                List.of("ghost"), List.of(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("不属于");

        var api = publishTable(setup, "guard-" + System.nanoTime() % 100000);

        // 切到另一租户：跨租户不可见
        String slugB = "xb" + (System.nanoTime() % 100000000);
        long b = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("B" + slugB, "admb" + slugB, "password123"));
        TenantContext.set(new TenantContext.Ctx(b, 2L, "admb", "admin"));
        assertThatThrownBy(() -> pubService.getOwned(api.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在");
        TenantContext.clear();
    }

    @Test
    void sizeAndOrderLimits() {
        var setup = setupTenantWithTable("as");
        var api = pubService.create(new ApiPubService.CreateReq(
                "限流API", "lim-" + System.nanoTime() % 100000, "TABLE", null, null,
                setup.datasourceId(), "public", setup.table(),
                List.of("id", "name"), List.of(), 2));
        var cred = pubService.credential(api.getId());
        var resolved = openApiService.resolve(api.getPath(), openApiService.authenticate(cred.apiKey(), cred.apiSecret()));
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
}
