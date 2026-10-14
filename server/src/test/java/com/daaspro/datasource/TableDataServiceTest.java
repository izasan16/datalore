package com.daaspro.datasource;

import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.datasource.service.TableDataService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TableDataServiceTest {

    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;
    @Autowired TableDataService tableDataService;

    private Long dsId;
    private String table;

    @BeforeEach
    void setUp() {
        String slug = tenantService.registerAutoSlug("表服务租户" + System.nanoTime(),
                "admtab" + System.nanoTime(), "password123");
        Long tid = tenantService.getTenantIdBySlug(slug);
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "admin", "admin"));
        var ds = dataSourceService.create("表服务PG", "postgresql", "localhost", 15432,
                "daaspro", "public", "daaspro", "daaspro_dev_2026");
        dsId = ds.getId();
        table = "t_tabsvc_" + System.nanoTime();
        execDdl("CREATE TABLE " + table + " (id bigserial PRIMARY KEY, name text, city text, qty int)");
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
                    "jdbc:postgresql://localhost:15432/daaspro", "daaspro", "daaspro_dev_2026");
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
            conn.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> row(String name, String city, String qty) {
        return Map.of("name", name, "city", city, "qty", qty);
    }

    @Test
    void insertRowsListFilterSortAndDelete() {
        tableDataService.insertRow(dsId, "public", table, row("张三", "北京", "5"));
        tableDataService.insertRow(dsId, "public", table, row("李四", "上海", "3"));
        tableDataService.insertRow(dsId, "public", table, row("王五", "北京", "8"));

        Map<String, Object> page = tableDataService.rows(dsId, "public", table, 1, 2, null, "id", "asc");
        assertThat(page.get("deletable")).isEqualTo(true);
        assertThat(((Number) page.get("total")).longValue()).isEqualTo(3L);
        assertThat((int) page.get("pageSize")).isEqualTo(2);
        List<?> rows = (List<?>) page.get("rows");
        assertThat(rows).hasSize(2);

        String filtersJson = "[{\"col\":\"city\",\"op\":\"eq\",\"value\":\"北京\"}]";
        Map<String, Object> filtered = tableDataService.rows(dsId, "public", table, 1, 50, filtersJson, null, null);
        assertThat(((Number) filtered.get("total")).longValue()).isEqualTo(2L);
        assertThat(filtered.get("rowIds")).isNotNull();

        String likeJson = "[{\"col\":\"name\",\"op\":\"like\",\"value\":\"张\"}]";
        Map<String, Object> liked = tableDataService.rows(dsId, "public", table, 1, 50, likeJson, null, null);
        assertThat(((Number) liked.get("total")).longValue()).isEqualTo(1L);

        List<String> rowIds = ((List<?>) filtered.get("rowIds")).stream().map(String::valueOf).toList();
        tableDataService.deleteRows(dsId, "public", table, List.of(rowIds.get(0)));
        Map<String, Object> after = tableDataService.rows(dsId, "public", table, 1, 50, filtersJson, null, null);
        assertThat(((Number) after.get("total")).longValue()).isEqualTo(1L);
    }

    @Test
    void rejectsBadColumnAndBadFilter() {
        assertThatThrownBy(() -> tableDataService.insertRow(dsId, "public", table,
                Map.of("evil; DROP TABLE x", "v")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tableDataService.rows(dsId, "public", table, 1, 50,
                "[{\"col\":\"city; DROP TABLE x\",\"op\":\"eq\",\"value\":\"1\"}]", null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tableDataService.rows(dsId, "public", table, 1, 50,
                "[{\"col\":\"city\",\"op\":\"drop table\",\"value\":\"1\"}]", null, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void columnDdlLifecycleOnPg() {
        tableDataService.columnDdl(dsId, "public", table, "addColumn", "age", null, "bigint");
        var cols = dataSourceService.listColumns(dsId, table, "public");
        assertThat(cols.stream().anyMatch(c -> "age".equals(c.get("name")))).isTrue();

        tableDataService.columnDdl(dsId, "public", table, "renameColumn", "age", "age2", null);
        cols = dataSourceService.listColumns(dsId, table, "public");
        assertThat(cols.stream().noneMatch(c -> "age".equals(c.get("name")))).isTrue();
        assertThat(cols.stream().anyMatch(c -> "age2".equals(c.get("name")))).isTrue();

        tableDataService.columnDdl(dsId, "public", table, "alterType", "age2", null, "numeric");
        cols = dataSourceService.listColumns(dsId, table, "public");
        assertThat(cols.stream().filter(c -> "age2".equals(c.get("name")))
                .anyMatch(c -> c.get("type").contains("numeric"))).isTrue();

        assertThatThrownBy(() -> tableDataService.columnDdl(dsId, "public", table, "dropColumn",
                "id", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主键");

        tableDataService.columnDdl(dsId, "public", table, "dropColumn", "age2", null, null);
        cols = dataSourceService.listColumns(dsId, table, "public");
        assertThat(cols.stream().noneMatch(c -> "age2".equals(c.get("name")))).isTrue();
    }
}
