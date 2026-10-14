package com.daaspro.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.mapper.DataSourceMapper;
import com.daaspro.tenant.context.TenantContext;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService {

    static final List<String> SUPPORTED = List.of("postgresql", "mysql", "sqlserver", "oracle");

    private final DataSourceMapper mapper;
    private final AesGcmCipher cipher;
    private final ConnectionManager connectionManager;

    public DataSourceService(DataSourceMapper mapper, AesGcmCipher cipher, ConnectionManager connectionManager) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.connectionManager = connectionManager;
    }

    public List<DataSourceEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getTenantId, TenantContext.requireTenantId())
                .orderByAsc(DataSourceEntity::getId));
    }

    public DataSourceEntity getOwned(Long id) {
        DataSourceEntity ds = mapper.selectById(id);
        if (ds == null || !ds.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "数据源不存在");
        }
        return ds;
    }

    public DataSourceEntity create(String name, String type, String host, Integer port,
                                   String databaseName, String schemaName, String username, String password) {
        if (!SUPPORTED.contains(type)) {
            throw new BizException(400, "不支持的数据源类型");
        }
        Long dup = mapper.selectCount(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getTenantId, TenantContext.requireTenantId())
                .eq(DataSourceEntity::getName, name));
        if (dup > 0) throw new BizException(400, "同名数据源已存在");

        DataSourceEntity ds = new DataSourceEntity();
        ds.setTenantId(TenantContext.requireTenantId());
        ds.setName(name);
        ds.setType(type);
        ds.setHost(host);
        ds.setPort(port != null ? port : JdbcUrls.of(type).defaultPort());
        ds.setDatabaseName(databaseName);
        ds.setSchemaName(schemaName);
        ds.setUsername(username);
        ds.setPasswordEnc(cipher.encrypt(password));
        ds.setStatus(1);
        mapper.insert(ds);
        return ds;
    }

    /** 编辑数据源；密码留空表示保持原密码。更新后关闭旧连接池，下次访问按新配置重建。 */
    public DataSourceEntity update(Long id, String name, String host, Integer port,
                                   String databaseName, String schemaName, String username, String password) {
        DataSourceEntity ds = getOwned(id);
        if (name != null && !name.isBlank() && !name.equals(ds.getName())) {
            Long dup = mapper.selectCount(new LambdaQueryWrapper<DataSourceEntity>()
                    .eq(DataSourceEntity::getTenantId, TenantContext.requireTenantId())
                    .eq(DataSourceEntity::getName, name.trim()));
            if (dup > 0) throw new BizException(400, "同名数据源已存在");
            ds.setName(name.trim());
        }
        if (host != null && !host.isBlank()) ds.setHost(host.trim());
        if (port != null) ds.setPort(port);
        if (databaseName != null && !databaseName.isBlank()) ds.setDatabaseName(databaseName.trim());
        ds.setSchemaName(schemaName == null || schemaName.isBlank() ? null : schemaName.trim());
        if (username != null && !username.isBlank()) ds.setUsername(username.trim());
        if (password != null && !password.isBlank()) ds.setPasswordEnc(cipher.encrypt(password));
        mapper.updateById(ds);
        connectionManager.closePool(id);
        return ds;
    }

    public void delete(Long id) {
        DataSourceEntity ds = getOwned(id);
        connectionManager.closePool(id);
        mapper.deleteById(id);
    }

    public void test(Long id) {
        DataSourceEntity ds = getOwned(id);
        testConnection(ds.getType(), ds.getHost(), ds.getPort(), ds.getDatabaseName(),
                ds.getSchemaName(), ds.getUsername(), cipher.decrypt(ds.getPasswordEnc()));
    }

    public void testConnection(String type, String host, Integer port, String db, String schema,
                               String username, String password) {
        String url = JdbcUrls.of(type).url(host, port, db, schema);
        try (var conn = DriverManager.getConnection(url, username, password)) {
            if (!conn.isValid(5)) throw new BizException(400, "连接校验失败");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "连接失败: " + rootMessage(e));
        }
    }

    public List<Map<String, String>> listSchemas(Long id) {
        DataSourceEntity ds = getOwned(id);
        String sql = JdbcUrls.of(ds.getType()).listSchemasSql();
        try (var conn = open(ds); var st = conn.prepareStatement(sql); var rs = st.executeQuery()) {
            var rows = new ArrayList<Map<String, String>>();
            while (rs.next()) rows.add(Map.of("name", rs.getString(1)));
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "浏览 schema 失败: " + rootMessage(e));
        }
    }

    public List<Map<String, String>> listTables(Long id, String schemaParam) {
        DataSourceEntity ds = getOwned(id);
        String schema = schemaParam != null && !schemaParam.isBlank() ? schemaParam.trim() : defaultSchema(ds);
        String sql = JdbcUrls.of(ds.getType()).listTablesSql(schema);
        try (var conn = open(ds); var st = conn.prepareStatement(sql)) {
            try (var rs = st.executeQuery()) {
                var rows = new ArrayList<Map<String, String>>();
                while (rs.next()) rows.add(Map.of("name", rs.getString(1)));
                return rows;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "浏览表失败: " + rootMessage(e));
        }
    }

    public List<Map<String, String>> listColumns(Long id, String table, String schemaParam) {
        DataSourceEntity ds = getOwned(id);
        String schema = schemaParam != null && !schemaParam.isBlank() ? schemaParam.trim() : defaultSchema(ds);
        String sql = JdbcUrls.of(ds.getType()).listColumnsSql(schema, table);
        try (var conn = open(ds); var st = conn.prepareStatement(sql); var rs = st.executeQuery()) {
            var rows = new ArrayList<Map<String, String>>();
            while (rs.next()) rows.add(Map.of("name", rs.getString(1), "type", rs.getString(2)));
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "浏览列失败: " + rootMessage(e));
        }
    }

    /** 表数据预览：列结构 + 前 limit 行 + COUNT(*) 行数。只读，标识符严格校验。 */
    public Map<String, Object> previewTable(Long id, String schemaParam, String table, int limit) {
        DataSourceEntity ds = getOwned(id);
        String schema = schemaParam == null || schemaParam.isBlank() ? defaultSchema(ds) : schemaParam.trim();
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        int capped = Math.max(1, Math.min(limit, 100));
        String qualified = quoteIdent(ds, schema) + "." + quoteIdent(ds, table);
        String limitClause = switch (ds.getType()) {
            case "sqlserver" -> "SELECT TOP " + capped + " * FROM " + qualified;
            case "oracle" -> "SELECT * FROM " + qualified + " FETCH FIRST " + capped + " ROWS ONLY";
            default -> "SELECT * FROM " + qualified + " LIMIT " + capped;
        };
        try (var conn = open(ds)) {
            long total;
            try (var st = conn.prepareStatement("SELECT count(*) FROM " + qualified);
                 var rs = st.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
            List<Map<String, String>> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            try (var st = conn.createStatement(); var rs = st.executeQuery(limitClause)) {
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(Map.of("name", md.getColumnLabel(i), "type", md.getColumnTypeName(i)));
                }
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.add(rs.getString(i));
                    rows.add(row);
                }
            }
            return Map.of("columns", columns, "rows", rows, "total", total);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "预览失败: " + rootMessage(e));
        }
    }

    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    private void validateIdent(String value, String label) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new BizException(400, label + "非法: " + value);
        }
    }

    /** MySQL 用反引号，其余（PG/SQL Server/Oracle）用双引号。 */
    private String quoteIdent(DataSourceEntity ds, String ident) {
        if ("mysql".equals(ds.getType())) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private java.sql.Connection open(DataSourceEntity ds) throws Exception {
        return connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc())).getConnection();
    }

    private String defaultSchema(DataSourceEntity ds) {
        if ("postgresql".equals(ds.getType())) {
            return ds.getSchemaName() == null ? "public" : ds.getSchemaName();
        }
        return switch (ds.getType()) {
            case "mysql" -> ds.getDatabaseName() == null ? "" : ds.getDatabaseName();
            case "sqlserver" -> "dbo";
            case "oracle" -> ds.getUsername() == null ? "" : ds.getUsername().toUpperCase();
            default -> ds.getSchemaName() == null ? "public" : ds.getSchemaName();
        };
    }

    public static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
