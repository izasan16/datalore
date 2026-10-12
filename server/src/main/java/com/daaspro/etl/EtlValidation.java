package com.daaspro.etl;

import com.daaspro.common.exception.BizException;
import com.daaspro.sqllab.SqlScanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只负责 ETL 配置的纯校验，避免控制器和执行器各自实现一套安全规则。 */
public final class EtlValidation {
    private static final Set<String> TYPES = Set.of("bigint", "numeric", "text", "timestamp", "date", "boolean");
    private static final Set<String> WRITE_MODES = Set.of("OVERWRITE", "APPEND", "UPSERT");
    private static final Pattern PARAM = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke|commit|rollback|begin|call|execute|copy)\\b|\\b(pg_catalog|information_schema)\\b");

    private EtlValidation() {}

    public record SelectValidation(List<String> parameters) {}
    public record FieldMapping(String source, String target, String type, boolean enabled) {}

    public static SelectValidation validateSelect(String sql) {
        if (sql == null || sql.isBlank()) throw new BizException(400, "SELECT 不能为空");
        String normalized = SqlScanner.normalize(sql).trim();
        if (normalized.isBlank()) throw new BizException(400, "SELECT 不能为空");
        if (SqlScanner.semicolons(normalized) > 0) throw new BizException(400, "只允许单条 SELECT");
        if (!"SELECT".equals(SqlScanner.firstKeyword(normalized)) && !"WITH".equals(SqlScanner.firstKeyword(normalized))) {
            throw new BizException(400, "只允许只读 SELECT");
        }
        if (DANGEROUS.matcher(normalized).find()) throw new BizException(400, "SQL 包含被禁止的语句或对象引用");
        var params = new ArrayList<String>();
        Matcher matcher = PARAM.matcher(normalized);
        while (matcher.find()) if (!params.contains(matcher.group(1))) params.add(matcher.group(1));
        return new SelectValidation(List.copyOf(params));
    }

    public static void validateFieldMappings(List<FieldMapping> mappings, List<String> upsertKeys, String writeMode) {
        if (mappings == null || mappings.isEmpty()) throw new BizException(400, "至少配置一个字段映射");
        if (!WRITE_MODES.contains(writeMode)) throw new BizException(400, "写入策略不支持");
        Set<String> targets = new HashSet<>();
        for (FieldMapping mapping : mappings) {
            if (mapping == null || mapping.source() == null || mapping.source().isBlank()
                    || mapping.target() == null || mapping.target().isBlank()) {
                throw new BizException(400, "字段映射不完整");
            }
            if (!TYPES.contains(mapping.type().toLowerCase(Locale.ROOT))) throw new BizException(400, "平台统一字段类型不支持");
            if (!targets.add(mapping.target())) throw new BizException(400, "目标字段不能重复: " + mapping.target());
        }
        List<String> keys = upsertKeys == null ? List.of() : upsertKeys;
        if ("UPSERT".equals(writeMode) && keys.isEmpty()) throw new BizException(400, "更新插入必须配置键字段");
        if (keys.stream().anyMatch(k -> !targets.contains(k))) throw new BizException(400, "更新插入键字段必须存在于目标字段映射中");
    }

    public static void rejectSameTable(Long sourceId, String sourceSchema, String sourceTable,
                                       Long targetId, String targetSchema, String targetTable) {
        if (sourceId != null && sourceId.equals(targetId)
                && same(sourceSchema, targetSchema) && same(sourceTable, targetTable)) {
            throw new BizException(400, "源表与目标表不能相同");
        }
    }

    private static boolean same(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }
}
