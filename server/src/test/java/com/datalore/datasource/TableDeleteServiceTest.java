package com.datalore.datasource;

import com.datalore.apipub.entity.ApiPubEntity;
import com.datalore.apipub.mapper.ApiPubMapper;
import com.datalore.common.exception.BizException;
import com.datalore.datasource.service.DataSourceService;
import com.datalore.datasource.service.TableDeleteService;
import com.datalore.etl.entity.EtlRunEntity;
import com.datalore.etl.entity.EtlTaskEntity;
import com.datalore.etl.mapper.EtlRunMapper;
import com.datalore.etl.mapper.EtlTaskMapper;
import com.datalore.imported.entity.ImportedTableEntity;
import com.datalore.imported.mapper.ImportedTableMapper;
import com.datalore.tenant.context.TenantContext;
import com.datalore.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TableDeleteServiceTest {

    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;
    @Autowired TableDeleteService tableDeleteService;
    @Autowired ApiPubMapper apiPubMapper;
    @Autowired EtlTaskMapper etlTaskMapper;
    @Autowired EtlRunMapper etlRunMapper;
    @Autowired ImportedTableMapper importedTableMapper;

    private Long dsId;
    private String table;
    private long adminUserId;

    @BeforeEach
    void setUp() {
        var reg = tenantService.register("删表租户" + System.nanoTime(),
                "delref" + String.valueOf(System.nanoTime()).substring(9),
                "admref" + String.valueOf(System.nanoTime()).substring(9), "password123");
        adminUserId = reg.adminUserId();
        Long tid = reg.tenantId();
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "admin", "admin"));
        var ds = dataSourceService.create("删表PG", "postgresql", "localhost", 15432,
                "datalore", "public", "datalore", "datalore_dev_2026", null);
        dsId = ds.getId();
        table = "t_delref_" + System.nanoTime();
        execDdl("CREATE TABLE " + table + " (id bigserial PRIMARY KEY, name text)");
        execDdl("INSERT INTO " + table + " (name) VALUES ('x')");
    }

    @AfterEach
    void cleanUp() {
        try {
            execDdl("DROP TABLE IF EXISTS " + table);
        } catch (Exception ignored) {
        }
        TenantContext.clear();
    }

    private void execDdl(String sql) {
        try {
            var conn = java.sql.DriverManager.getConnection(
                    "jdbc:postgresql://localhost:15432/datalore", "datalore", "datalore_dev_2026");
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
            conn.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void refsDetectAllTypesAndForceDropCleansImported() {
        Long tid = TenantContext.requireTenantId();
        String tag = String.valueOf(System.nanoTime());

        ApiPubEntity api = new ApiPubEntity();
        api.setTenantId(tid);
        api.setName("删表引用API");
        api.setPath("delref-" + tag);
        api.setColumns("[\"name\"]");
        api.setDatasourceId(dsId);
        api.setSchemaName("public");
        api.setTableName(table);
        api.setStatus(1);
        apiPubMapper.insert(api);

        EtlTaskEntity task = new EtlTaskEntity();
        task.setTenantId(tid);
        task.setName("删表引用ETL" + tag);
        task.setSourceDatasourceId(dsId);
        task.setTargetDatasourceId(dsId);
        task.setStatus("ACTIVE");
        task.setInputType("TABLE");
        task.setSourceSchema("public");
        task.setSourceTable(table);
        task.setTargetSchema("public");
        task.setTargetTable("t_delref_tgt_" + tag);
        task.setCreatedBy(adminUserId);
        etlTaskMapper.insert(task);
        EtlRunEntity run = new EtlRunEntity();
        run.setTenantId(tid);
        run.setTaskId(task.getId());
        run.setTriggerType("MANUAL");
        run.setStatus("RUNNING");
        run.setConfigVersion(1);
        etlRunMapper.insert(run);

        ImportedTableEntity rec = new ImportedTableEntity();
        rec.setTenantId(tid);
        rec.setName(table);
        rec.setTableName(table);
        rec.setSchemaName("public");
        rec.setDatasourceId(dsId);
        rec.setColumnsDef("[]");
        rec.setFileName("x.csv");
        rec.setRowCount(1L);
        importedTableMapper.insert(rec);

        List<Map<String, Object>> refs = tableDeleteService.refs(dsId, "public", table);
        assertThat(refs).hasSize(3);
        assertThat(refs.stream().anyMatch(r -> "etl_source".equals(r.get("type"))
                && Boolean.TRUE.equals(r.get("running")))).isTrue();
        assertThat(refs.stream().anyMatch(r -> "api_pub".equals(r.get("type"))
                && Boolean.TRUE.equals(r.get("enabled")))).isTrue();
        assertThat(refs.stream().anyMatch(r -> "imported".equals(r.get("type")))).isTrue();

        // 强删：物理表删除 + 导入记录清理
        tableDeleteService.drop(dsId, "public", table);
        assertThat(refs.stream().anyMatch(r -> "imported".equals(r.get("type")))).isTrue();
        assertThat(importedTableMapper.selectById(rec.getId())).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execDdl("SELECT * FROM " + table))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void dropWithoutRefsJustDrops() {
        tableDeleteService.drop(dsId, "public", table);
        assertThat(tableDeleteService.refs(dsId, "public", table)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execDdl("SELECT * FROM " + table))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsIllegalIdentifiers() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                tableDeleteService.refs(dsId, "public", "x; drop table y"))
                .isInstanceOf(BizException.class);
    }
}
