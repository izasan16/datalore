package com.daaspro.datasource.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.daaspro.datasource.entity.DataSourceEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按数据源 id 缓存的 Hikari 连接池；外部源用注册信息，内置源指向平台 PG 的租户 schema。 */
@Component
public class ConnectionManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final String platformUrl;
    private final String platformUser;
    private final String platformPassword;

    public ConnectionManager(@Value("${spring.datasource.url}") String platformUrl,
                             @Value("${spring.datasource.username}") String platformUser,
                             @Value("${spring.datasource.password}") String platformPassword) {
        this.platformUrl = platformUrl;
        this.platformUser = platformUser;
        this.platformPassword = platformPassword;
    }

    public DataSource pool(DataSourceEntity ds, String password) {
        return pools.computeIfAbsent(ds.getId(), id -> {
            JdbcUrls t = JdbcUrls.of(ds.getType());
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(t.url(ds.getHost(), ds.getPort(), ds.getDatabaseName(), ds.getSchemaName()));
            cfg.setUsername(ds.getUsername());
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(5);
            cfg.setMinimumIdle(0);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(60_000);
            cfg.setPoolName("ds-" + id);
            return new HikariDataSource(cfg);
        });
    }

    public DataSource builtinPool(Long datasourceId, String tenantSchema) {
        return pools.computeIfAbsent(datasourceId, id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(platformUrl);
            cfg.setUsername(platformUser);
            cfg.setPassword(platformPassword);
            cfg.setMaximumPoolSize(5);
            cfg.setMinimumIdle(0);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(60_000);
            cfg.setPoolName("builtin-" + id);
            cfg.addDataSourceProperty("currentSchema", tenantSchema);
            return new HikariDataSource(cfg);
        });
    }

    public void closePool(Long datasourceId) {
        var ds = pools.remove(datasourceId);
        if (ds != null) ds.close();
    }
}
