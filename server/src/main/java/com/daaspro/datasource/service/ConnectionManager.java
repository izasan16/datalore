package com.daaspro.datasource.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.entity.DataSourceEntity;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按数据源 id 缓存的 Hikari 连接池，一律使用数据源注册信息。 */
@Component
public class ConnectionManager {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSource pool(DataSourceEntity ds, String password) {
        return pools.computeIfAbsent(ds.getId(), id -> {
            try {
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
            } catch (RuntimeException e) {
                // 池初始化失败（连不上/认证失败等）透传数据库原生根因，不做 500 包装
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                throw new BizException(400, root.getMessage() == null ? "数据源连接失败" : root.getMessage());
            }
        });
    }

    public void closePool(Long datasourceId) {
        var ds = pools.remove(datasourceId);
        if (ds != null) ds.close();
    }
}
