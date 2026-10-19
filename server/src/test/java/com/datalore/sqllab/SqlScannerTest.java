package com.datalore.sqllab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScannerTest {

    @Test
    void stripsLineComment() {
        assertThat(SqlScanner.normalize("select 1 -- drop table x;"))
                .doesNotContain("drop");
    }

    @Test
    void stripsBlockComment() {
        assertThat(SqlScanner.normalize("select /* ; drop x */ 1")).isEqualTo("select   1");
    }

    @Test
    void stripsNestedBlockComment() {
        // PG 块注释可嵌套
        assertThat(SqlScanner.normalize("select /* a /* b */ c */ 1")).isEqualTo("select   1");
    }

    @Test
    void stringsBecomePlaceholder() {
        assertThat(SqlScanner.normalize("select 'a;b', 'it''s'")).isEqualTo("select '', ''");
    }

    @Test
    void dollarQuotesBecomePlaceholder() {
        assertThat(SqlScanner.normalize("select $tag$a;b$tag$")).isEqualTo("select ''");
    }

    @Test
    void firstKeyword() {
        assertThat(SqlScanner.firstKeyword(SqlScanner.normalize("  with t as (select 1) select * from t")))
                .isEqualTo("WITH");
        assertThat(SqlScanner.firstKeyword(SqlScanner.normalize("SELECT 1"))).isEqualTo("SELECT");
        assertThat(SqlScanner.firstKeyword("")).isEmpty();
    }

    @Test
    void semicolonsIgnoreTrailing() {
        assertThat(SqlScanner.semicolons(SqlScanner.normalize("select 1"))).isZero();
        assertThat(SqlScanner.semicolons(SqlScanner.normalize("select 1;"))).isZero();
        assertThat(SqlScanner.semicolons(SqlScanner.normalize("select 1; select 2"))).isEqualTo(1);
        assertThat(SqlScanner.semicolons(SqlScanner.normalize("select ';'"))).isZero();
    }

    @Test
    void tenantRefs() {
        var refs = SqlScanner.tenantRefs(SqlScanner.normalize("select * from tenant_a.t cross join public.b"));
        assertThat(refs).containsExactly("tenant_a");
        // 字符串中的 tenant 引用必须被剥离
        assertThat(SqlScanner.tenantRefs(SqlScanner.normalize("select 'tenant_secret'")))
                .isEmpty();
    }
}
