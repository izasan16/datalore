package com.daaspro.imported;

import com.daaspro.common.exception.BizException;
import com.daaspro.imported.service.ImportedTableService;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.daaspro.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传导入到用户自有数据源：建表导入 + 追加 + 预览。 */
@SpringBootTest
@Transactional
class ImportedTableImportTest {

    @Autowired ImportedTableService ImportedTableService;
    @Autowired TenantService tenantService;
    @Autowired DataSourceService dataSourceService;

    private record Ctx(long tenantId, long dsId, String slug) {}

    private Ctx setup() {
        String slug = "di" + (System.nanoTime() % 100000000);
        long tid = tenantService.getTenantIdBySlug(
                tenantService.registerAutoSlug("导入租户" + slug, "adm" + slug, "password123"));
        TenantContext.set(new TenantContext.Ctx(tid, 1L, "adm", "admin"));
        var ds = dataSourceService.create("导入PG" + slug, "postgresql", "localhost", 15432,
                "daaspro", "public", "daaspro", "daaspro_dev_2026");
        return new Ctx(tid, ds.getId(), slug);
    }

    private MockMultipartFile csv() {
        String content = "id,city,amount\n1,SH,10.5\n2,BJ,20\n3,SH,33.7\n";
        return new MockMultipartFile("file", "orders.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importsCreatesTableAndAppendWorks() {
        var ctx = setup();
        var up = ImportedTableService.upload(csv());
        var created = ImportedTableService.confirm(new ImportedTableService.ConfirmReq(
                up.uploadId(), "导入订单" + ctx.slug(), "orders.csv", up.columns(), up.types(), ctx.dsId(), null, null));
        assertThat(created.getRowCount()).isEqualTo(3);
        assertThat(created.getDatasourceId()).isEqualTo(ctx.dsId());

        var preview = ImportedTableService.preview(created.getId());
        assertThat(preview.rows()).hasSize(3);
        assertThat(preview.columns()).extracting(c -> c.get("name"))
                .containsExactly("id", "city", "amount");

        var up2 = ImportedTableService.upload(csv());
        var appended = ImportedTableService.confirm(new ImportedTableService.ConfirmReq(
                up2.uploadId(), null, "orders.csv", up2.columns(), up2.types(), ctx.dsId(), null, created.getTableName()));
        assertThat(appended.getId()).isEqualTo(created.getId());
        assertThat(appended.getRowCount()).isEqualTo(6);

        // 外部库连接不在测试事务内，显式清理物理表
        ImportedTableService.delete(created.getId());
    }

    @Test
    void datasourceIdRequired() {
        var ctx = setup();
        var up = ImportedTableService.upload(csv());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ImportedTableService.confirm(
                        new ImportedTableService.ConfirmReq(up.uploadId(), "无源导入", "orders.csv",
                                up.columns(), up.types(), null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请选择目标数据源");
    }
}
