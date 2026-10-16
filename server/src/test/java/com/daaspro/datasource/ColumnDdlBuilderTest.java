package com.daaspro.datasource;

import com.daaspro.common.exception.BizException;
import com.daaspro.datasource.service.ColumnDdlBuilder;
import com.daaspro.datasource.service.ColumnDdlBuilder.Col;
import com.daaspro.datasource.service.ColumnDdlBuilder.Target;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字段批量 DDL 语句生成（纯函数）。 */
class ColumnDdlBuilderTest {

    private static final Col NAME = new Col("name", "character varying(50)", true, null, null);

    @Test
    void pgSplitsStatementsPerAttribute() {
        var current = new Col("title", "character varying(50)", true, null, null);
        var target = new Target("update", "title", "varchar", 100, null, null, false, "abc", "备注");
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", current, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE \"public\".\"t\" ALTER COLUMN \"title\" TYPE VARCHAR(100) USING \"title\"::VARCHAR(100)",
            "ALTER TABLE \"public\".\"t\" ALTER COLUMN \"title\" SET NOT NULL",
            "ALTER TABLE \"public\".\"t\" ALTER COLUMN \"title\" SET DEFAULT 'abc'",
            "COMMENT ON COLUMN \"public\".\"t\".\"title\" IS '备注'");
    }

    @Test
    void pgNoChangeEmitsNothing() {
        // base=null 表示保持现有类型；默认值空=清除，但当前无默认值
        var target = new Target("update", "name", null, null, null, null, true, "", null);
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", NAME, target);
        assertThat(stmts).isEmpty();
    }

    @Test
    void pgClearDefaultDropsDefault() {
        var col = new Col("age", "integer", true, "0", null);
        var target = new Target("update", "age", null, null, null, null, null, "", null);
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", col, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE \"public\".\"t\" ALTER COLUMN \"age\" DROP DEFAULT");
    }

    @Test
    void pgRenameComesFirstAndUsesNewName() {
        var target = new Target("update", "title", "text", null, null, null, null, null, null);
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", NAME, target);
        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0)).contains("RENAME COLUMN \"name\" TO \"title\"");
        assertThat(stmts.get(1)).contains("ALTER COLUMN \"title\" TYPE TEXT");
    }

    @Test
    void mysqlMergesIntoSingleModify() {
        var col = new Col("name", "varchar(50)", true, null, "旧注释");
        var target = new Target("update", "name", "varchar", 100, null, null, false, "x", "新注释");
        var stmts = ColumnDdlBuilder.build("mysql", "demo", "t", col, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE `demo`.`t` MODIFY COLUMN `name` VARCHAR(100) NOT NULL DEFAULT 'x' COMMENT '新注释'");
    }

    @Test
    void mysqlCarriesUnchangedDefaultAndComment() {
        var col = new Col("name", "varchar(50)", true, "'abc'", "旧注释");
        // 保持类型、默认值不变（归一化比较去引号）、注释不变 → 无语句
        var target = new Target("update", "name", null, null, null, null, true, "abc", "旧注释");
        var stmts = ColumnDdlBuilder.build("mysql", "demo", "t", col, target);
        assertThat(stmts).isEmpty();
    }

    @Test
    void keepTypeRejectsDefaultChange() {
        var col = new Col("geom", "geometry", true, null, null);
        var target = new Target("update", "geom", "__keep__", null, null, null, true, "x", null);
        assertThatThrownBy(() -> ColumnDdlBuilder.build("postgresql", "public", "t", col, target))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不在类型目录中");
    }

    @Test
    void sqlServerRejectsDefaultAndComment() {
        var col = new Col("name", "nvarchar(50)", true, null, null);
        assertThatThrownBy(() -> ColumnDdlBuilder.build("sqlserver", "dbo", "t", col,
                new Target("update", "name", "nvarchar", 100, null, null, true, "x", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("SQL Server 暂不支持修改默认值");
        assertThatThrownBy(() -> ColumnDdlBuilder.build("sqlserver", "dbo", "t", col,
                new Target("update", "name", "nvarchar", 100, null, null, true, null, "c")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("SQL Server 暂不支持修改列注释");
    }

    @Test
    void addColumnWithAttributes() {
        var target = new Target("add", "code", "varchar", 20, null, null, false, "A1", "编码");
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", NAME, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE \"public\".\"t\" ADD COLUMN \"code\" VARCHAR(20) DEFAULT 'A1' NOT NULL",
            "COMMENT ON COLUMN \"public\".\"t\".\"code\" IS '编码'");

        var mysqlStmts = ColumnDdlBuilder.build("mysql", "demo", "t", NAME, target);
        assertThat(mysqlStmts).containsExactly(
            "ALTER TABLE `demo`.`t` ADD COLUMN `code` VARCHAR(20) NOT NULL DEFAULT 'A1' COMMENT '编码'");
    }

    @Test
    void hiveAddUsesAddColumns() {
        var target = new Target("add", "code", "varchar", 20, null, null, true, null, "编码");
        var stmts = ColumnDdlBuilder.build("hive", "default", "t", NAME, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE `default`.`t` ADD COLUMNS (`code` VARCHAR(20) COMMENT '编码')");
    }

    @Test
    void hiveUpdateMergesIntoChangeColumn() {
        var col = new Col("name", "string", true, null, null);
        var target = new Target("update", "title", "string", null, null, null, true, null, null);
        var stmts = ColumnDdlBuilder.build("hive", "default", "t", col, target);
        assertThat(stmts).containsExactly(
            "ALTER TABLE `default`.`t` CHANGE COLUMN `name` `title` STRING");
    }

    @Test
    void hiveRejectsNotNullAndDefault() {
        var col = new Col("name", "string", true, null, null);
        assertThatThrownBy(() -> ColumnDdlBuilder.build("hive", "default", "t", col,
                new Target("update", "name", "string", null, null, null, false, null, null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("NOT NULL");
        assertThatThrownBy(() -> ColumnDdlBuilder.build("hive", "default", "t", col,
                new Target("add", "code", "bigint", null, null, null, true, "1", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("默认值");
    }

    @Test
    void currentTimestampWhitelistAndInvalidLiteral() {
        var col = new Col("created", "TIMESTAMP", true, null, null);
        var stmts = ColumnDdlBuilder.build("postgresql", "public", "t", col,
            new Target("update", "created", "timestamp", null, null, null, true, "CURRENT_TIMESTAMP", null));
        assertThat(stmts).containsExactly(
            "ALTER TABLE \"public\".\"t\" ALTER COLUMN \"created\" SET DEFAULT CURRENT_TIMESTAMP");

        assertThatThrownBy(() -> ColumnDdlBuilder.build("postgresql", "public", "t", col,
            new Target("update", "created", "timestamp", null, null, null, true, "not-a-date", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("默认值");
    }
}
