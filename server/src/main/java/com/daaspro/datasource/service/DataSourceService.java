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

    static final List<String> COMPUTE_TYPES = List.of("spark", "flink");

    static final List<String> SUPPORTED = List.of("postgresql", "mysql", "sqlserver", "oracle", "hive", "spark", "flink");

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
        ds.setPort(port != null ? port : defaultPortFor(type));
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
        if ("spark".equals(type) || "flink".equals(type)) { testComputeEngine(type, host, port); return; }
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
        if (COMPUTE_TYPES.contains(ds.getType())) return List.of();
        if ("hive".equals(ds.getType())) {
            try (var conn = open(ds); var st = conn.createStatement(); var rs = st.executeQuery("SHOW DATABASES")) {
                var rows = new ArrayList<Map<String, String>>();
                while (rs.next()) rows.add(Map.of("name", rs.getString(1)));
                return rows;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(400, "浏览 schema 失败: " + rootMessage(e));
            }
        }
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
        if (COMPUTE_TYPES.contains(ds.getType())) return List.of();
        String schema = schemaParam != null && !schemaParam.isBlank() ? schemaParam.trim() : defaultSchema(ds);
        String sql = JdbcUrls.of(ds.getType()).listTablesSql(schema);
        if ("hive".equals(ds.getType())) {
            try (var conn = open(ds); var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
                var rows = new ArrayList<Map<String, String>>();
                while (rs.next()) rows.add(Map.of("name", rs.getString(1)));
                return rows;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(400, "浏览表失败: " + rootMessage(e));
            }
        }
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
        if (COMPUTE_TYPES.contains(ds.getType())) return List.of();
        String schema = schemaParam != null && !schemaParam.isBlank() ? schemaParam.trim() : defaultSchema(ds);
        String sql = JdbcUrls.of(ds.getType()).listColumnsSql(schema, table);
        if ("hive".equals(ds.getType())) {
            return listHiveColumns(ds, sql);
        }
        try (var conn = open(ds); var st = conn.prepareStatement(sql); var rs = st.executeQuery()) {
            var rows = new ArrayList<Map<String, String>>();
            while (rs.next()) {
                String nullable = rs.getString(3);
                String normalized = "YES".equalsIgnoreCase(nullable) || "Y".equalsIgnoreCase(nullable) ? "YES" : "NO";
                Map<String, String> row = new java.util.LinkedHashMap<>();
                row.put("name", rs.getString(1));
                row.put("type", rs.getString(2));
                row.put("nullable", normalized);
                row.put("default", rs.getString(4) == null ? null : rs.getString(4).trim());
                row.put("comment", rs.getString(5));
                row.put("charLength", rs.getObject(6) == null ? null : String.valueOf(rs.getLong(6)));
                row.put("numericPrecision", rs.getObject(7) == null ? null : String.valueOf(rs.getInt(7)));
                row.put("numericScale", rs.getObject(8) == null ? null : String.valueOf(rs.getInt(8)));
                rows.add(row);
            }
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "浏览列失败: " + rootMessage(e));
        }
    }

    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_$]{0,62}$");

    private void validateIdent(String value, String label) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new BizException(400, label + "非法: " + value);
        }
    }

    /** MySQL/Hive 用反引号，其余（PG/SQL Server/Oracle）用双引号。 */
    private String quoteIdent(DataSourceEntity ds, String ident) {
        if ("mysql".equals(ds.getType()) || "hive".equals(ds.getType())) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** Hive DESCRIBE 输出解析：col_name / data_type / comment，跳过分隔与分区头行。 */
    private List<Map<String, String>> listHiveColumns(DataSourceEntity ds, String sql) {
        try (var conn = open(ds); var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            var rows = new ArrayList<Map<String, String>>();
            while (rs.next()) {
                String name = rs.getString(1);
                String type = rs.getString(2);
                String comment = rs.getString(3);
                if (name == null || name.isBlank() || name.trim().startsWith("#")) continue;
                java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                row.put("name", name.trim());
                row.put("type", type == null ? "string" : type.trim());
                row.put("nullable", "YES");
                row.put("default", null);
                row.put("comment", comment == null || comment.isBlank() ? null : comment.trim());
                String ty = row.get("type").toLowerCase();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(varchar|char)\\((\\d+)\\)").matcher(ty);
                if (m.matches()) row.put("charLength", m.group(2));
                java.util.regex.Matcher d = java.util.regex.Pattern
                        .compile("decimal\\((\\d+),(\\d+)\\)").matcher(ty);
                if (d.matches()) {
                    row.put("numericPrecision", d.group(1));
                    row.put("numericScale", d.group(2));
                }
                rows.add(row);
            }
            return rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "浏览列失败: " + rootMessage(e));
        }
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
            case "hive" -> ds.getSchemaName() == null || ds.getSchemaName().isBlank() ? "default" : ds.getSchemaName();
            default -> ds.getSchemaName() == null ? "public" : ds.getSchemaName();
        };
    }

    public 
    static int defaultPortFor(String type) {
        return switch (type) {
            case "spark" -> 8998;
            case "flink" -> 8081;
            default -> JdbcUrls.of(type).defaultPort();
        };
    }

    private void testComputeEngine(String type, String host, Integer port) {
        String base = "http://" + host + ":" + (port != null ? port : defaultPortFor(type));
        String path = "spark".equals(type) ? "/batches" : "/overview";
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(base + path)).GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new BizException(400, "计算引擎连接失败 (HTTP " + resp.statusCode() + ")");
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "计算引擎连接失败: " + rootMessage(e)); }
    }

    public static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
