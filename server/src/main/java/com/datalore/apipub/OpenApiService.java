package com.datalore.apipub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datalore.apipub.entity.ApiPubEntity;
import com.datalore.apipub.mapper.ApiPubMapper;
import com.datalore.common.exception.BizException;
import com.datalore.common.security.AesGcmCipher;
import com.datalore.datasource.entity.DataSourceEntity;
import com.datalore.datasource.mapper.DataSourceMapper;
import com.datalore.datasource.service.ConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 开放接口执行端（免登录，凭证走 X-Api-Key / X-Api-Secret 头）。
 * 安全要点：
 * - 凭证直接定位 api_pub（每接口独立 Key/Secret，constant-time 比对）。
 * - TABLE 模式：仅 SELECT 暴露列，过滤值全部 PreparedStatement 占位符；列名/表名只来自发布配置。
 * - SQL 模式：发布时已做只读校验，:param 替换为占位符，值按参数定义绑定；行数上限 setMaxRows 截断。
 * - path 不存在/停用/凭证无效一律 401/404（不泄露存在性）。
 */
@Service
public class OpenApiService {
    private static final Logger log = LoggerFactory.getLogger(OpenApiService.class);

    public record OpenResult(List<String> columns, List<List<String>> rows, long total, int page, int size) {}

    /** SQL 命名参数：与 EtlValidation.PARAM 同规则，避免匹配 :: 类型转换 */
    private static final Pattern PARAM = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private final ApiPubMapper pubMapper;
    private final DataSourceMapper dsMapper;
    private final ConnectionManager connectionManager;
    private final AesGcmCipher cipher;
    private final ApiPubService pubService;

    public OpenApiService(ApiPubMapper pubMapper, DataSourceMapper dsMapper,
                          ConnectionManager connectionManager, AesGcmCipher cipher, ApiPubService pubService) {
        this.pubMapper = pubMapper;
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

    /** 校验接口凭证，返回对应 api_pub；失败抛 401。 */
    public ApiPubEntity authenticate(String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw unauthorized();
        }
        ApiPubEntity api = pubMapper.selectOne(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getApiKey, apiKey));
        if (api == null || api.getApiSecretEnc() == null || api.getApiSecretEnc().isBlank()) throw unauthorized();
        byte[] expected;
        byte[] actual;
        try {
            expected = cipher.decrypt(api.getApiSecretEnc()).getBytes(StandardCharsets.UTF_8);
            actual = apiSecret.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw unauthorized();
        }
        if (!MessageDigest.isEqual(expected, actual)) throw unauthorized();
        return api;
    }

    /** 解析 path → 启用的 API + 数据源；不存在/停用 → 404。以 byPath 实时数据为准。 */
    public Resolved resolve(String path, ApiPubEntity api) {
        ApiPubEntity byPath = api == null ? null : pubMapper.selectOne(new LambdaQueryWrapper<ApiPubEntity>()
                .eq(ApiPubEntity::getPath, path));
        if (byPath == null || !byPath.getId().equals(api.getId())
                || byPath.getStatus() == null || byPath.getStatus() != 1) {
            throw new OpenApiException(404, "API 不存在或已停用");
        }
        DataSourceEntity ds = byPath.getDatasourceId() == null ? null : dsMapper.selectById(byPath.getDatasourceId());
        if (ds == null) {
            throw new OpenApiException(404, "API 配置异常：数据源缺失");
        }
        return new Resolved(byPath, ds);
    }

    public record Resolved(ApiPubEntity api, DataSourceEntity datasource) {}

    /** 执行查询。queryParams 为 URL 上除 page/size/order_by/order 外的参数。 */
    public OpenResult query(Resolved r, Map<String, String> queryParams, String pageStr, String sizeStr,
                            String orderBy, String order) {
        return "SQL".equals(r.api().getSourceType())
                ? querySql(r, queryParams)
                : queryTable(r, queryParams, pageStr, sizeStr, orderBy, order);
    }

    // ---------- TABLE 模式 ----------

    private OpenResult queryTable(Resolved r, Map<String, String> queryParams,
                                  String pageStr, String sizeStr, String orderBy, String order) {
        String dsType = r.datasource().getType();
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

        String cols = exposed.stream().map(c -> quote(dsType, c)).collect(Collectors.joining(", "));
        String schema = r.api().getSchemaName();
        String table = schema == null || schema.isBlank()
                ? quote(dsType, r.api().getTableName())
                : quote(dsType, schema) + "." + quote(dsType, r.api().getTableName());
        String where = "";
        List<String> bind = new ArrayList<>();
        if (!effective.isEmpty()) {
            where = " WHERE " + effective.keySet().stream().map(c -> quote(dsType, c) + " = ?")
                    .collect(Collectors.joining(" AND "));
            bind.addAll(effective.values());
        }
        String orderSql = "";
        if (orderBy != null && !orderBy.isBlank()) {
            if (!exposedSet.contains(orderBy)) {
                throw new OpenApiException(400, "order_by 必须是暴露列");
            }
            boolean desc = "desc".equalsIgnoreCase(order);
            orderSql = " ORDER BY " + quote(dsType, orderBy) + (desc ? " DESC" : " ASC");
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
            boolean hive = "hive".equals(dsType);
            int skip = hive ? (page - 1) * size : 0;
            List<List<String>> rows = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT " + cols + " FROM " + table + where + orderSql
                            + (hive ? " LIMIT ?" : " LIMIT ? OFFSET ?"))) {
                int idx = 1;
                for (String v : bind) st.setString(idx++, v);
                st.setInt(idx++, hive ? page * size : size);
                if (!hive) st.setInt(idx, (page - 1) * size);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        if (skip > 0) { skip--; continue; }
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= exposed.size(); i++) row.add(rs.getString(i));
                        rows.add(row);
                    }
                }
            }
            return new OpenResult(new ArrayList<>(exposed), rows, total, page, size);
        } catch (OpenApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAPI 查询执行失败", e);
            throw new OpenApiException(500, "查询执行失败");
        }
    }

    // ---------- SQL 模式 ----------

    private OpenResult querySql(Resolved r, Map<String, String> queryParams) {
        ApiPubEntity api = r.api();
        List<ApiPubService.ParamDef> defs = pubService.parseParams(api.getParamsJson());
        Map<String, ApiPubService.ParamDef> defMap = new LinkedHashMap<>();
        for (ApiPubService.ParamDef d : defs) defMap.put(d.name(), d);

        // 解析 SQL 中的参数出现顺序，并把 :param 替换为 ?
        String sql = api.getSqlText();
        StringBuilder sb = new StringBuilder();
        List<String> order = new ArrayList<>();
        Matcher m = PARAM.matcher(sql);
        int last = 0;
        while (m.find()) {
            sb.append(sql, last, m.start()).append('?');
            order.add(m.group(1));
            last = m.end();
        }
        sb.append(sql.substring(last));

        // 绑定值：必填缺失 → 400；可选缺失 → 默认值，否则 NULL；未知参数 → 400
        List<Object> bind = new ArrayList<>();
        for (String name : order) {
            ApiPubService.ParamDef def = defMap.get(name);
            if (def == null) throw new OpenApiException(500, "参数配置缺失: " + name);
            String v = queryParams.get(name);
            if (v == null || v.isBlank()) {
                if (Boolean.FALSE.equals(def.required()) && def.defaultValue() != null && !def.defaultValue().isBlank()) {
                    v = def.defaultValue();
                } else if (Boolean.FALSE.equals(def.required())) {
                    bind.add(null);
                    continue;
                } else {
                    throw new OpenApiException(400, "缺少必填参数: " + name);
                }
            }
            if ("number".equals(def.type())) {
                try {
                    bind.add(new BigDecimal(v.trim()));
                } catch (NumberFormatException e) {
                    throw new OpenApiException(400, "参数 " + name + " 必须是数字");
                }
            } else {
                bind.add(v);
            }
        }
        for (String k : queryParams.keySet()) {
            if (!defMap.containsKey(k)) throw new OpenApiException(400, "不支持的查询参数: " + k);
        }

        int maxRows = api.getMaxRows() == null || api.getMaxRows() <= 0 ? 1000 : api.getMaxRows();
        DataSource pool = connectionManager.pool(r.datasource(), cipher.decrypt(r.datasource().getPasswordEnc()));
        try (Connection conn = pool.getConnection();
             PreparedStatement st = conn.prepareStatement(sb.toString())) {
            st.setMaxRows(maxRows);
            int idx = 1;
            for (Object v : bind) {
                if (v == null) st.setNull(idx++, java.sql.Types.NULL);
                else if (v instanceof BigDecimal bd) st.setBigDecimal(idx++, bd);
                else st.setString(idx++, (String) v);
            }
            List<String> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                while (rs.next()) {
                    List<String> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) row.add(rs.getString(i));
                    rows.add(row);
                }
            }
            return new OpenResult(columns, rows, rows.size(), 1, Math.max(rows.size(), 1));
        } catch (OpenApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAPI 查询执行失败", e);
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

    /** MySQL/Hive 反引号，其余双引号。 */
    private String quote(String dsType, String ident) {
        if ("mysql".equals(dsType) || "hive".equals(dsType)) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
