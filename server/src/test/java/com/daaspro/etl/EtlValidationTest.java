package com.daaspro.etl;

import com.daaspro.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EtlValidationTest {

    @Test
    void acceptsReadOnlySelectAndExtractsNamedParameters() {
        var result = EtlValidation.validateSelect(
                "SELECT id, name FROM users WHERE city = :city AND created_at >= :fromDate");

        assertThat(result.parameters()).containsExactly("city", "fromDate");
    }

    @Test
    void rejectsWriteStatementsAndMultipleStatements() {
        assertThatThrownBy(() -> EtlValidation.validateSelect("UPDATE users SET name = 'x'"))
                .isInstanceOf(BizException.class).hasMessageContaining("只允许");
        assertThatThrownBy(() -> EtlValidation.validateSelect("SELECT 1; DELETE FROM users"))
                .isInstanceOf(BizException.class).hasMessageContaining("单条");
        assertThatThrownBy(() -> EtlValidation.validateSelect("SELECT * FROM pg_catalog.pg_user"))
                .isInstanceOf(BizException.class).hasMessageContaining("禁止");
    }

    @Test
    void validatesFieldMappingsAndUpsertKeys() {
        var mappings = List.of(
                new EtlValidation.FieldMapping("id", "customer_id", "bigint", true),
                new EtlValidation.FieldMapping("name", "customer_name", "text", true));
        EtlValidation.validateFieldMappings(mappings, List.of("customer_id"), "APPEND");

        assertThatThrownBy(() -> EtlValidation.validateFieldMappings(mappings,
                List.of("missing"), "UPSERT"))
                .isInstanceOf(BizException.class).hasMessageContaining("更新插入");
        assertThatThrownBy(() -> EtlValidation.validateFieldMappings(List.of(
                new EtlValidation.FieldMapping("id", "id", "unsupported", true)), List.of(), "APPEND"))
                .isInstanceOf(BizException.class).hasMessageContaining("类型");
    }

    @Test
    void rejectsSameSourceAndTargetTable() {
        assertThatThrownBy(() -> EtlValidation.rejectSameTable(7L, "public", "orders",
                7L, "public", "orders"))
                .isInstanceOf(BizException.class).hasMessageContaining("相同");
    }
}
