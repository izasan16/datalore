package com.daaspro.sqllab;

import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.ConnectionManager;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.datasource.service.JdbcUrls;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 网关（MVP 安全模型，详见 docs/superpowers/plans/2026-09-15-05-sqllab.md）：
 * 1) 校验基于 normalize 文本（注释/字符串剥离），防绕过也防误杀；
 * 2) 单语句；3) 首关键字白名单（内置源 DDL/DML/查询；外部源只读）；
 * 4) 模式黑名单（pg 函数、public 与 information_schema 前缀、其它租户 schema、提权与运维语句）；
 * 5) 连接级 setQueryTimeout + setMaxRows。
 * 已知残余风险：非常见 schema 的全限定名探测——后续以「每租户独立 DB 角色 + grants」强化。
 */
@Service
public class SqlGatewayService {

    private static final Set<String> BUILTIN_FIRST = Set.of(
            "SELECT", "WITH", "INSERT", "UPDATE", "DELETE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "SHOW", "EXPLAIN", "VALUES");
    private static final Set<String> EXTERNAL_FIRST = Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "VALUES");

    private static final List<Pattern> BLACKLIST = List.of(
            Pattern.compile("(?i)\\bpg_(?!_)[a-z_]+\\s*\\("),              // pg_* 函数（pg_sleep/pg_catalog 等）
            Pattern.compile("(?i)\\bpg_catalog\\b"),
            Pattern.compile("(?i)\\binformation_schema\\s*\\."),
            Pattern.compile("(?i)\\bpublic\\s*\\."),
            Pattern.compile("(?i)\\bgrant\\b"),
            Pattern.compile("(?i)\\brevoke\\b"),
            Pattern.compile("(?i)\\bset\\s+(role|session_authorization)\\b"),
            Pattern.compile("(?i)\\bcopy\\b"),
            Pattern.compile("(?i)\\bvacuum\\b"),
            Pattern.compile("(?i)\\balter\\s+system\\b"),
            Pattern.compile("(?i)\\bdo\\s+\\$|\\blanguage\\s"),
            Pattern.compile("(?i)\\bpg_read_file|\\bpg_ls_dir|\\blo_import|\\blo_export\\b"),
            Pattern.compile("(?i)\\bdblink\\b"));

    private final ConnectionManager connectionManager;
    private final DataSourceService dataSourceService;
    private final AesGcmCipher cipher;
    private final int maxRows;
    private final int maxSeconds;

    public SqlGatewayService(ConnectionManager connectionManager, DataSourceService dataSourceService,
                             AesGcmCipher cipher,
                             @Value("${daaspro.sql.max-rows:10000}") int maxRows,
                             @Value("${daaspro.sql.max-seconds:30}") int maxSeconds) {
        this.connectionManager = connectionManager;
        this.dataSourceService = dataSourceService;
        this.cipher = cipher;
        this.maxRows = maxRows;
        this.maxSeconds = maxSeconds;
    }

    public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated,
                              int rowCount, long elapsedMs, boolean isQuery, int affectedRows) {}

    public QueryResult run(String datasourceIdStr, String sql) {
        if (sql == null || sql.isBlank()) throw new BizException(400, "SQL 不能为空");
        String normalized = SqlScanner.normalize(sql);
        if (normalized.isBlank()) throw new BizException(400, "SQL 不能为空");
        if (SqlScanner.semicolons(normalized) > 0) {
            throw new BizException(400, "只允许执行单条语句");
        }
        String first = SqlScanner.firstKeyword(normalized);
        if (first.isEmpty()) throw new BizException(400, "无法识别的 SQL");

        DataSourceEntity ds = resolveTarget(datasourceIdStr);
        boolean builtin = Boolean.TRUE.equals(ds.getBuiltin());
        if (!(builtin ? BUILTIN_FIRST : EXTERNAL_FIRST).contains(first)) {
            throw new BizException(400, builtin ? "该语句类型不允许执行: " + first : "外部数据源仅支持查询语句");
        }
        for (Pattern p : BLACKLIST) {
            if (p.matcher(normalized).find()) {
                throw new BizException(400, "SQL 包含被禁止的语句或对象引用");
            }
        }
        String ownSchema = builtin ? ds.getSchemaName() : null;
        for (String ref : SqlScanner.tenantRefs(normalized)) {
            if (ownSchema == null || !ref.equals(ownSchema.toLowerCase(Locale.ROOT))) {
                throw new BizException(400, "不允许引用其他租户的数据空间");
            }
        }

        DataSource pool = builtin
                ? connectionManager.builtinPool(ds.getId(), ds.getSchemaName())
                : connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc()));

        long t0 = System.currentTimeMillis();
        try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
            st.setQueryTimeout(maxSeconds);
            st.setMaxRows(maxRows + 1);
            boolean hasRs = st.execute(sql);
            long elapsed = System.currentTimeMillis() - t0;
            if (hasRs) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) columns.add(md.getColumnLabel(i));
                    List<List<String>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        List<String> row = new ArrayList<>(cols);
                        for (int i = 1; i <= cols; i++) row.add(rs.getString(i));
                        rows.add(row);
                    }
                    return new QueryResult(columns, rows, truncated, rows.size(), elapsed, true, 0);
                }
            }
            return new QueryResult(List.of(), List.of(), false, 0, elapsed, false, st.getUpdateCount());
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.toLowerCase(Locale.ROOT).contains("cancel")) {
                throw new BizException(400, "查询超时（超过 " + maxSeconds + " 秒）已取消");
            }
            throw new BizException(400, "SQL 错误: " + msg);
        }
    }

    private DataSourceEntity resolveTarget(String datasourceIdStr) {
        if (datasourceIdStr == null || datasourceIdStr.isBlank()) {
            return dataSourceService.list().stream()
                    .filter(d -> Boolean.TRUE.equals(d.getBuiltin())).findFirst()
                    .orElseThrow(() -> new BizException(400, "内置数据源缺失"));
        }
        long id;
        try {
            id = Long.parseLong(datasourceIdStr.trim());
        } catch (NumberFormatException e) {
            throw new BizException(400, "数据源 id 非法");
        }
        return dataSourceService.getOwned(id);
    }
}
