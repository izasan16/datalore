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
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(.+)$");
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT", "KEY", "INDEX", "EXCLUDE");

    private EtlValidation() {}

    public record SelectValidation(List<String> parameters) {}
    public record FieldMapping(String source, String target, String type, boolean enabled) {}
    public record ParsedColumn(String name, String rawType, String platformType) {}
    public record ParsedCreateTable(String schema, String table, List<ParsedColumn> columns) {}

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

    /** 解析单条 CREATE TABLE 语句，用于自动建表模式：返回表名（可带 schema）和列列表。 */
    public static ParsedCreateTable parseCreateTable(String ddl) {
        if (ddl == null || ddl.isBlank()) throw new BizException(400, "建表语句不能为空");
        String normalized = SqlScanner.normalize(ddl).trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        if (SqlScanner.semicolons(normalized) > 0) throw new BizException(400, "建表语句只允许单条 CREATE TABLE");
        Matcher head = CREATE_TABLE.matcher(normalized);
        if (!head.matches()) throw new BizException(400, "只允许 CREATE TABLE 建表语句");
        String rest = head.group(1).trim();
        int open = rest.indexOf('(');
        if (open < 0) throw new BizException(400, "建表语句缺少字段定义");
        String tableName = rest.substring(0, open).trim();
        int close = matchingParen(rest, open);
        if (!rest.substring(close + 1).isBlank()) throw new BizException(400, "建表语句末尾包含不支持的内容");
        List<String> nameParts = splitQualified(tableName);
        if (nameParts.isEmpty() || nameParts.size() > 2) throw new BizException(400, "目标表名不合法");
        String table = nameParts.get(nameParts.size() - 1);
        String schema = nameParts.size() == 2 ? nameParts.get(0) : null;
        List<ParsedColumn> columns = parseColumns(rest.substring(open + 1, close));
        return new ParsedCreateTable(schema, table, columns);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTick = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingle) { if (c == '\'') inSingle = false; continue; }
            if (inDouble) { if (c == '"') inDouble = false; continue; }
            if (inTick) { if (c == '`') inTick = false; continue; }
            if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '`') inTick = true;
            else if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
        }
        throw new BizException(400, "建表语句括号不匹配");
    }

    private static List<String> splitQualified(String name) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < name.length()) {
            while (i < name.length() && Character.isWhitespace(name.charAt(i))) i++;
            if (i >= name.length()) break;
            StringBuilder part = new StringBuilder();
            if (name.charAt(i) == '"' || name.charAt(i) == '`') {
                char q = name.charAt(i);
                i++;
                while (i < name.length() && name.charAt(i) != q) part.append(name.charAt(i++));
                if (i >= name.length()) throw new BizException(400, "目标表名不合法");
                i++;
            } else {
                while (i < name.length() && !Character.isWhitespace(name.charAt(i)) && name.charAt(i) != '.') {
                    part.append(name.charAt(i++));
                }
            }
            while (i < name.length() && Character.isWhitespace(name.charAt(i))) i++;
            if (i < name.length()) {
                if (name.charAt(i) != '.') throw new BizException(400, "目标表名不合法");
                i++;
            }
            if (part.isEmpty()) throw new BizException(400, "目标表名不合法");
            parts.add(part.toString());
        }
        return parts;
    }

    private static List<ParsedColumn> parseColumns(String body) {
        List<String> defs = splitTopLevel(body);
        List<ParsedColumn> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String def : defs) {
            String trimmed = def.trim();
            if (trimmed.isEmpty()) continue;
            String[] head = firstToken(trimmed);
            String name = head[0];
            String remainder = head[1];
            if (name.startsWith("\"") || name.startsWith("`")) name = unquote(name);
            else if (!name.matches("(?i)[\\p{L}_][\\p{L}\\p{N}_$]*")) throw new BizException(400, "字段名不合法: " + name);
            if (CONSTRAINT_KEYWORDS.contains(name.toUpperCase(Locale.ROOT))) continue;
            if (remainder.isBlank()) throw new BizException(400, "字段缺少类型: " + name);
            String[] typeParts = firstToken(remainder);
            String rawType = typeParts[0] + typeParts[2];
            if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new BizException(400, "字段名重复: " + name);
            columns.add(new ParsedColumn(name, rawType, platformType(rawType)));
        }
        if (columns.isEmpty()) throw new BizException(400, "建表语句至少需要一个字段");
        return List.copyOf(columns);
    }

    /** 返回 [首个 token, 剩余文本, token 后紧跟的括号段]。 */
    private static String[] firstToken(String text) {
        String t = text.trim();
        int i = 0;
        String token;
        if (t.startsWith("\"") || t.startsWith("`")) {
            char q = t.charAt(0);
            int close = t.indexOf(q, 1);
            if (close < 0) throw new BizException(400, "引号不匹配");
            token = t.substring(0, close + 1);
            i = close + 1;
        } else {
            while (i < t.length() && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '(') i++;
            token = t.substring(0, i);
        }
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        String parenPart = "";
        if (i < t.length() && t.charAt(i) == '(') {
            int close = matchingParen(t, i);
            parenPart = t.substring(i, close + 1);
            i = close + 1;
        }
        return new String[]{token, t.substring(i).trim(), parenPart};
    }

    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1);
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTick = false;
        StringBuilder current = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (inSingle) { current.append(c); if (c == '\'') inSingle = false; continue; }
            if (inDouble) { current.append(c); if (c == '"') inDouble = false; continue; }
            if (inTick) { current.append(c); if (c == '`') inTick = false; continue; }
            if (c == '\'') { inSingle = true; current.append(c); }
            else if (c == '"') { inDouble = true; current.append(c); }
            else if (c == '`') { inTick = true; current.append(c); }
            else if (c == '(') { depth++; current.append(c); }
            else if (c == ')') { depth--; current.append(c); }
            else if (c == ',' && depth == 0) { parts.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    public static String platformType(String raw) {
        String t = (raw == null ? "" : raw.toLowerCase(Locale.ROOT));
        if (t.contains("bool")) return "boolean";
        if (t.equals("date")) return "date";
        if (t.contains("timestamp") || t.contains("datetime")) return "timestamp";
        if (t.contains("int") || t.contains("serial")) return "bigint";
        if (t.contains("numeric") || t.contains("decimal") || t.contains("double")
                || t.contains("float") || t.contains("real") || t.contains("money")) return "numeric";
        return "text";
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
