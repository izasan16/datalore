package com.daaspro.apipub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.apipub.entity.ApiAppEntity;
import com.daaspro.apipub.entity.ApiGrantEntity;
import com.daaspro.apipub.entity.ApiPubEntity;
import com.daaspro.apipub.mapper.ApiAppMapper;
import com.daaspro.apipub.mapper.ApiGrantMapper;
import com.daaspro.apipub.mapper.ApiPubMapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.mapper.DataSourceMapper;
import com.daaspro.datasource.service.ConnectionManager;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放接口执行端（免登录，凭证走 X-Api-Key / X-Api-Secret 头）。
 * 安全要点：
 * - 只读：仅 SELECT 暴露列，过滤值全部 PreparedStatement 占位符；列名/表名只来自发布配置（双引号转义）。
 * - order_by 必须是暴露列；size 上限 max_rows；page ≥ 1。
 * - secret 校验：AES 解密后 constant-time 比较。path 不存在/停用/未授权一律 404（不泄露存在性）。
 * 已知取舍：密钥明文头传输依赖 HTTPS；无限流与调用审计（后续迭代）。
 */
@Service
public class OpenApiService {

    public record OpenResult(List<String> columns, List<List<String>> rows, long total, int page, int size) {}

    private final ApiPubMapper pubMapper;
    private final ApiAppMapper appMapper;
    private final ApiGrantMapper grantMapper;
    private final DataSourceMapper dsMapper;
    private final ConnectionManager connectionManager;
    private final AesGcmCipher cipher;
    private final ApiPubService pubService;

    public OpenApiService(ApiPubMapper pubMapper, ApiAppMapper appMapper, ApiGrantMapper grantMapper,
                          DataSourceMapper dsMapper,
                          ConnectionManager connectionManager, AesGcmCipher cipher, ApiPubService pubService) {
        this.pubMapper = pubMapper;
        this.appMapper = appMapper;
        this.grantMapper = grantMapper;
        this.dsMapper = dsMapper;
        this.connectionManager = connectionManager;
        this.cipher = cipher;
        this.pubService = pubService;
    }

    /** 鉴权失败异常：带 HTTP 状态（401/404/400）。 */
    public static class OpenApiException extends RuntimeException {
        public final int status;
        public final String bodyMessage;
        public OpenApiException(int status, String message) {
            super(message);
            this.status = status;
            this.bodyMessage = message;
        }
    }

    public OpenApiException unauthorized() {
        return new OpenApiException(401, "调用凭证无效");
    }

    /** 校验应用凭证，返回 app；失败抛 401。 */
    public ApiAppEntity authenticate(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw unauthorized();
        }
        ApiAppEntity app = appMapper.selectOne(new LambdaQueryWrapper<ApiAppEntity>()
                .eq(ApiAppEntity::getAppKey, appKey));
        if (app == null) throw unauthorized();
        byte[] expected;
        byte[] actual;
        try {
            expected = cipher.decrypt(app.getAppSecretEnc()).getBytes(StandardCharsets.UTF_8);
            actual = appSecret.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw unauthorized();
        }
        if (!MessageDigest.isEqual(expected, actual)) throw unauthorized();
        return app;
    }

    /** 解析 path → 启用的 API + 数据源；未授权/不存在/停用 → 404。 */
    public Resolved resolve(String path, ApiAppEntity app) {
        ApiPubEntity api = pubMapper.selectOne(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getPath, path));
        if (api == null || api.getStatus() == null || api.getStatus() != 1) {
            throw new OpenApiException(404, "API 不存在或已停用");
        }
        if (!api.getTenantId().equals(app.getTenantId())) {
            // 跨租户 path 不允许撞车（发布时全局唯一），这里再兜底防数据异常
            throw new OpenApiException(404, "API 不存在或已停用");
        }
        long granted = grantMapper.selectCount(new LambdaQueryWrapper<ApiGrantEntity>()
                .eq(ApiGrantEntity::getApiId, api.getId())
                .eq(ApiGrantEntity::getAppId, app.getId()));
        if (granted == 0) throw new OpenApiException(404, "该应用未被授权调用此 API");
        DataSourceEntity ds = api.getDatasourceId() == null ? null : dsMapper.selectById(api.getDatasourceId());
        if (ds == null) {
            throw new OpenApiException(404, "API 配置异常：数据源缺失");
        }
        return new Resolved(api, ds);
    }

    public record Resolved(ApiPubEntity api, DataSourceEntity datasource) {}

    /** 执行查询。queryParams 为 URL 上除 page/size/order_by/order 外的参数。 */
    public OpenResult query(Resolved r, Map<String, String> queryParams, String pageStr, String sizeStr,
                            String orderBy, String order) {
        List<String> exposed = pubService.parseJson(r.api().getColumns());
        Set<String> exposedSet = exposed.stream().collect(Collectors.toSet());
        List<String> filters = pubService.parseJson(r.api().getFilterColumns());

        int page = parseInt(pageStr, 1, 1, 1_000_000, "page");
        int maxSize = r.api().getMaxRows() == null || r.api().getMaxRows() <= 0 ? 1000 : r.api().getMaxRows();
        int size = clampSize(sizeStr, maxSize);

        // 未知参数拒绝，过滤参数必须白名单内
        Map<String, String> effective = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            if (!filters.contains(e.getKey())) {
                throw new OpenApiException(400, "不支持的查询参数: " + e.getKey());
            }
            effective.put(e.getKey(), e.getValue());
        }

        String cols = exposed.stream().map(c -> quote(c)).collect(Collectors.joining(", "));
        String schema = r.api().getSchemaName();
        String table = schema == null || schema.isBlank()
                ? quote(r.api().getTableName())
                : quote(schema) + "." + quote(r.api().getTableName());
        StringBuilder where = new StringBuilder();
        List<String> bind = new ArrayList<>();
        if (!effective.isEmpty()) {
            where.append(" WHERE ");
            where.append(effective.keySet().stream().map(c -> quote(c) + " = ?")
                    .collect(Collectors.joining(" AND ")));
            bind.addAll(effective.values());
        }
        String orderSql = "";
        if (orderBy != null && !orderBy.isBlank()) {
            if (!exposedSet.contains(orderBy)) {
                throw new OpenApiException(400, "order_by 必须是暴露列");
            }
            boolean desc = "desc".equalsIgnoreCase(order);
            orderSql = " ORDER BY " + quote(orderBy) + (desc ? " DESC" : " ASC");
        }

        DataSource pool = connectionManager.pool(r.datasource(), cipher.decrypt(r.datasource().getPasswordEnc()));
        try (Connection conn = pool.getConnection()) {
            long total;
            try (PreparedStatement st = conn.prepareStatement("SELECT count(*) FROM " + table + where)) {
                bindParams(st, bind);
                try (ResultSet rs = st.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            List<List<String>> rows = new ArrayList<>();
            List<String> columns = new ArrayList<>(exposed);
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT " + cols + " FROM " + table + where + orderSql + " LIMIT ? OFFSET ?")) {
                int idx = 1;
                for (String v : bind) st.setString(idx++, v);
                st.setInt(idx++, size);
                st.setInt(idx, (page - 1) * size);
                try (ResultSet rs = st.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    // 列名以发布配置顺序为准（PG 返回小写标签）
                    while (rs.next()) {
                        List<String> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) row.add(rs.getString(i));
                        rows.add(row);
                    }
                }
            }
            return new OpenResult(columns, rows, total, page, size);
        } catch (OpenApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenApiException(500, "查询执行失败");
        }
    }

    private void bindParams(PreparedStatement st, List<String> bind) throws Exception {
        for (int i = 0; i < bind.size(); i++) st.setString(i + 1, bind.get(i));
    }

    private int parseInt(String v, int def, int min, int max, String name) {
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < min || n > max) {
                throw new OpenApiException(400, name + " 超出范围 (" + min + ".." + max + ")");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new OpenApiException(400, name + " 必须是整数");
        }
    }

    /** size 超上限时钳制而非报错（对外更友好，且不会泄密）。 */
    private int clampSize(String v, int max) {
        if (v == null || v.isBlank()) return max;
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new OpenApiException(400, "size 必须是整数");
        }
        if (n < 1) throw new OpenApiException(400, "size 必须大于 0");
        return Math.min(n, max);
    }

    private String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
