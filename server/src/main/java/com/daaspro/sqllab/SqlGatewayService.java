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

/**
 * SQL 网关：平台不代理权限管控，语句类型不限制，权限由用户数据库自身决定；
 * 平台只保留运行保护：单条语句、setQueryTimeout、setMaxRows；错误透传数据库原生消息。
 */
@Service
public class SqlGatewayService {

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
        DataSourceEntity ds = resolveTarget(datasourceIdStr);
        DataSource pool = connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc()));

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
            throw new BizException(400, msg);
        }
    }

    private DataSourceEntity resolveTarget(String datasourceIdStr) {
        if (datasourceIdStr == null || datasourceIdStr.isBlank()) {
            throw new BizException(400, "请先选择数据源");
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
