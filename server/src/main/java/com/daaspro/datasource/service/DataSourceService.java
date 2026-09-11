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
                .orderByDesc(DataSourceEntity::getBuiltin)
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
        ds.setBuiltin(false);
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

    public void delete(Long id) {
        DataSourceEntity ds = getOwned(id);
        if (Boolean.TRUE.equals(ds.getBuiltin())) {
            throw new BizException(400, "内置数据源不可删除");
        }
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

    public List<Map<String, String>> listTables(Long id) {
        DataSourceEntity ds = getOwned(id);
        boolean builtin = Boolean.TRUE.equals(ds.getBuiltin());
        String sql = builtin
                ? "SELECT tablename FROM pg_tables WHERE schemaname = ? ORDER BY 1"
                : JdbcUrls.of(ds.getType()).listTablesSql(ds.getSchemaName() == null ? "public" : ds.getSchemaName());
        try (var conn = open(ds); var st = conn.prepareStatement(sql)) {
            if (builtin) st.setString(1, ds.getSchemaName());
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

    public List<Map<String, String>> listColumns(Long id, String table) {
        DataSourceEntity ds = getOwned(id);
        String schema = Boolean.TRUE.equals(ds.getBuiltin()) ? ds.getSchemaName()
                : (ds.getSchemaName() == null ? "public" : ds.getSchemaName());
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

    private java.sql.Connection open(DataSourceEntity ds) throws Exception {
        if (Boolean.TRUE.equals(ds.getBuiltin())) {
            return connectionManager.builtinPool(ds.getId(), ds.getSchemaName()).getConnection();
        }
        return connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc())).getConnection();
    }

    public static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
