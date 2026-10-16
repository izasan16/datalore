package com.daaspro.datasource.service;

import com.daaspro.common.exception.BizException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按数据源方言的原生列类型目录。
 * DDL 中的类型串只能由白名单 base + 服务端校验过的参数拼装，前端不传原始类型串。
 */
public final class ColumnTypes {

    public enum Family { INT, DECIMAL, BOOL, TEXT, DATETIME, OTHER }

    public enum Param { NONE, LENGTH, PRECISION }

    /** base=目录键（小写）；native=DDL 中的原生写法；aliases=元数据 data_type 匹配别名。 */
    public record TypeDef(String base, String nativeName, String label, Param param, Family family,
                          List<String> aliases) {}

    private static final Map<String, List<TypeDef>> CATALOG = Map.of(
        "postgresql", List.of(
            new TypeDef("smallint", "SMALLINT", "smallint", Param.NONE, Family.INT, List.of("smallint")),
            new TypeDef("integer", "INTEGER", "integer", Param.NONE, Family.INT, List.of("integer", "int")),
            new TypeDef("bigint", "BIGINT", "bigint", Param.NONE, Family.INT, List.of("bigint", "int8")),
            new TypeDef("numeric", "NUMERIC", "numeric(p,s)", Param.PRECISION, Family.DECIMAL, List.of("numeric")),
            new TypeDef("real", "REAL", "real", Param.NONE, Family.DECIMAL, List.of("real", "float4")),
            new TypeDef("double precision", "DOUBLE PRECISION", "double precision", Param.NONE, Family.DECIMAL, List.of("double precision", "float8")),
            new TypeDef("text", "TEXT", "text", Param.NONE, Family.TEXT, List.of("text")),
            new TypeDef("varchar", "VARCHAR", "varchar(n)", Param.LENGTH, Family.TEXT, List.of("character varying", "varchar")),
            new TypeDef("char", "CHAR", "char(n)", Param.LENGTH, Family.TEXT, List.of("character", "char", "bpchar")),
            new TypeDef("boolean", "BOOLEAN", "boolean", Param.NONE, Family.BOOL, List.of("boolean", "bool")),
            new TypeDef("date", "DATE", "date", Param.NONE, Family.DATETIME, List.of("date")),
            new TypeDef("timestamp", "TIMESTAMP", "timestamp", Param.NONE, Family.DATETIME, List.of("timestamp without time zone", "timestamp")),
            new TypeDef("timestamptz", "TIMESTAMPTZ", "timestamptz", Param.NONE, Family.DATETIME, List.of("timestamp with time zone", "timestamptz")),
            new TypeDef("time", "TIME", "time", Param.NONE, Family.DATETIME, List.of("time without time zone", "time")),
            new TypeDef("uuid", "UUID", "uuid", Param.NONE, Family.OTHER, List.of("uuid")),
            new TypeDef("json", "JSON", "json", Param.NONE, Family.OTHER, List.of("json")),
            new TypeDef("jsonb", "JSONB", "jsonb", Param.NONE, Family.OTHER, List.of("jsonb"))
        ),
        "mysql", List.of(
            new TypeDef("tinyint", "TINYINT", "tinyint", Param.NONE, Family.INT, List.of("tinyint")),
            new TypeDef("smallint", "SMALLINT", "smallint", Param.NONE, Family.INT, List.of("smallint")),
            new TypeDef("int", "INT", "int", Param.NONE, Family.INT, List.of("int", "integer")),
            new TypeDef("bigint", "BIGINT", "bigint", Param.NONE, Family.INT, List.of("bigint")),
            new TypeDef("decimal", "DECIMAL", "decimal(p,s)", Param.PRECISION, Family.DECIMAL, List.of("decimal")),
            new TypeDef("float", "FLOAT", "float", Param.NONE, Family.DECIMAL, List.of("float")),
            new TypeDef("double", "DOUBLE", "double", Param.NONE, Family.DECIMAL, List.of("double")),
            new TypeDef("varchar", "VARCHAR", "varchar(n)", Param.LENGTH, Family.TEXT, List.of("varchar")),
            new TypeDef("char", "CHAR", "char(n)", Param.LENGTH, Family.TEXT, List.of("char")),
            new TypeDef("text", "TEXT", "text", Param.NONE, Family.TEXT, List.of("text")),
            new TypeDef("longtext", "LONGTEXT", "longtext", Param.NONE, Family.TEXT, List.of("longtext")),
            new TypeDef("date", "DATE", "date", Param.NONE, Family.DATETIME, List.of("date")),
            new TypeDef("datetime", "DATETIME", "datetime", Param.NONE, Family.DATETIME, List.of("datetime")),
            new TypeDef("timestamp", "TIMESTAMP", "timestamp", Param.NONE, Family.DATETIME, List.of("timestamp")),
            new TypeDef("time", "TIME", "time", Param.NONE, Family.DATETIME, List.of("time")),
            new TypeDef("json", "JSON", "json", Param.NONE, Family.OTHER, List.of("json"))
        ),
        "sqlserver", List.of(
            new TypeDef("bit", "BIT", "bit", Param.NONE, Family.BOOL, List.of("bit")),
            new TypeDef("tinyint", "TINYINT", "tinyint", Param.NONE, Family.INT, List.of("tinyint")),
            new TypeDef("smallint", "SMALLINT", "smallint", Param.NONE, Family.INT, List.of("smallint")),
            new TypeDef("int", "INT", "int", Param.NONE, Family.INT, List.of("int")),
            new TypeDef("bigint", "BIGINT", "bigint", Param.NONE, Family.INT, List.of("bigint")),
            new TypeDef("decimal", "DECIMAL", "decimal(p,s)", Param.PRECISION, Family.DECIMAL, List.of("decimal", "numeric")),
            new TypeDef("float", "FLOAT", "float", Param.NONE, Family.DECIMAL, List.of("float")),
            new TypeDef("real", "REAL", "real", Param.NONE, Family.DECIMAL, List.of("real")),
            new TypeDef("varchar", "VARCHAR", "varchar(n)", Param.LENGTH, Family.TEXT, List.of("varchar")),
            new TypeDef("nvarchar", "NVARCHAR", "nvarchar(n)", Param.LENGTH, Family.TEXT, List.of("nvarchar")),
            new TypeDef("char", "CHAR", "char(n)", Param.LENGTH, Family.TEXT, List.of("char")),
            new TypeDef("date", "DATE", "date", Param.NONE, Family.DATETIME, List.of("date")),
            new TypeDef("time", "TIME", "time", Param.NONE, Family.DATETIME, List.of("time")),
            new TypeDef("datetime", "DATETIME", "datetime", Param.NONE, Family.DATETIME, List.of("datetime")),
            new TypeDef("datetime2", "DATETIME2", "datetime2", Param.NONE, Family.DATETIME, List.of("datetime2")),
            new TypeDef("uniqueidentifier", "UNIQUEIDENTIFIER", "uniqueidentifier", Param.NONE, Family.OTHER, List.of("uniqueidentifier"))
        ),
        "oracle", List.of(
            new TypeDef("number", "NUMBER", "number(p,s)", Param.PRECISION, Family.DECIMAL, List.of("number")),
            new TypeDef("integer", "INTEGER", "integer", Param.NONE, Family.INT, List.of("integer")),
            new TypeDef("float", "FLOAT", "float", Param.NONE, Family.DECIMAL, List.of("float")),
            new TypeDef("varchar2", "VARCHAR2", "varchar2(n)", Param.LENGTH, Family.TEXT, List.of("varchar2")),
            new TypeDef("nvarchar2", "NVARCHAR2", "nvarchar2(n)", Param.LENGTH, Family.TEXT, List.of("nvarchar2")),
            new TypeDef("char", "CHAR", "char(n)", Param.LENGTH, Family.TEXT, List.of("char")),
            new TypeDef("clob", "CLOB", "clob", Param.NONE, Family.TEXT, List.of("clob")),
            new TypeDef("nclob", "NCLOB", "nclob", Param.NONE, Family.TEXT, List.of("nclob")),
            new TypeDef("date", "DATE", "date", Param.NONE, Family.DATETIME, List.of("date")),
            new TypeDef("timestamp", "TIMESTAMP", "timestamp", Param.NONE, Family.DATETIME, List.of("timestamp")),
            new TypeDef("blob", "BLOB", "blob", Param.NONE, Family.OTHER, List.of("blob"))
        ),
        "hive", List.of(
            new TypeDef("tinyint", "TINYINT", "tinyint", Param.NONE, Family.INT, List.of("tinyint")),
            new TypeDef("smallint", "SMALLINT", "smallint", Param.NONE, Family.INT, List.of("smallint")),
            new TypeDef("int", "INT", "int", Param.NONE, Family.INT, List.of("int", "integer")),
            new TypeDef("bigint", "BIGINT", "bigint", Param.NONE, Family.INT, List.of("bigint")),
            new TypeDef("float", "FLOAT", "float", Param.NONE, Family.DECIMAL, List.of("float")),
            new TypeDef("double", "DOUBLE", "double", Param.NONE, Family.DECIMAL, List.of("double")),
            new TypeDef("decimal", "DECIMAL", "decimal(p,s)", Param.PRECISION, Family.DECIMAL, List.of("decimal")),
            new TypeDef("string", "STRING", "string", Param.NONE, Family.TEXT, List.of("string")),
            new TypeDef("varchar", "VARCHAR", "varchar(n)", Param.LENGTH, Family.TEXT, List.of("varchar")),
            new TypeDef("char", "CHAR", "char(n)", Param.LENGTH, Family.TEXT, List.of("char")),
            new TypeDef("boolean", "BOOLEAN", "boolean", Param.NONE, Family.BOOL, List.of("boolean")),
            new TypeDef("timestamp", "TIMESTAMP", "timestamp", Param.NONE, Family.DATETIME, List.of("timestamp")),
            new TypeDef("date", "DATE", "date", Param.NONE, Family.DATETIME, List.of("date"))
        )
    );

    /** 字符类长度上限（varchar/nchar 系）。 */
    private static final Map<String, Integer> LENGTH_LIMIT = Map.of(
        "mysql", 16383, "sqlserver", 8000, "oracle", 4000, "postgresql", 10485760, "hive", 65535
    );

    private ColumnTypes() {}

    public static List<TypeDef> of(String dsType) {
        List<TypeDef> list = CATALOG.get(dsType);
        if (list == null) throw new BizException(400, "不支持的数据源类型: " + dsType);
        return list;
    }

    public static TypeDef find(String dsType, String base) {
        for (TypeDef def : of(dsType)) {
            if (def.base().equalsIgnoreCase(base)) return def;
        }
        return null;
    }

    /** 按元数据 data_type + 参数定位目录项；不在目录中返回 null。 */
    public static TypeDef match(String dsType, String dataType, Integer charLength,
                                Integer numPrecision, Integer numScale) {
        String dt = dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT);
        for (TypeDef def : of(dsType)) {
            boolean alias = def.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(dt));
            if (!alias) continue;
            if (def.param() == Param.NONE) return def;
            if (def.param() == Param.LENGTH) {
                if (charLength != null && charLength > 0) return def;
            } else {
                if (numPrecision != null) return def;
            }
        }
        return null;
    }

    /** 校验参数并拼装 DDL 类型串。 */
    public static String build(String dsType, String base, Integer length, Integer precision, Integer scale) {
        TypeDef def = find(dsType, base);
        if (def == null) throw new BizException(400, "不支持的字段类型: " + base);
        return switch (def.param()) {
            case NONE -> def.nativeName();
            case LENGTH -> {
                if (length == null || length < 1) throw new BizException(400, def.label() + " 需要长度参数");
                int limit = LENGTH_LIMIT.getOrDefault(dsType, 8000);
                if (length > limit) throw new BizException(400, "长度超出上限 " + limit + ": " + length);
                yield def.nativeName() + "(" + length + ")";
            }
            case PRECISION -> {
                if (precision == null || precision < 1 || precision > 38)
                    throw new BizException(400, "精度必须在 1-38 之间: " + precision);
                if (scale == null) scale = 0;
                if (scale < 0 || scale > precision) throw new BizException(400, "小数位必须在 0-精度之间: " + scale);
                yield def.nativeName() + "(" + precision + "," + scale + ")";
            }
        };
    }

    public static Family family(String dsType, String base) {
        TypeDef def = find(dsType, base);
        if (def == null) throw new BizException(400, "不支持的字段类型: " + base);
        return def.family();
    }

    /**
     * 按类型族校验并拼装默认值字面量；null/空串返回 null 表示清除默认值。
     * DATETIME 族允许 CURRENT_TIMESTAMP / CURRENT_DATE / CURRENT_TIME 白名单表达式。
     */
    public static String defaultLiteral(Family family, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        switch (family) {
            case INT -> {
                try { Long.parseLong(v); return v; } catch (NumberFormatException e) { /* fallthrough */ }
                throw new BizException(400, "整数类型的默认值必须是整数: " + v);
            }
            case DECIMAL -> {
                try { new BigDecimal(v); return v; } catch (NumberFormatException e) { /* fallthrough */ }
                throw new BizException(400, "数值类型的默认值必须是数值: " + v);
            }
            case BOOL -> {
                if (v.equalsIgnoreCase("TRUE")) return "TRUE";
                if (v.equalsIgnoreCase("FALSE")) return "FALSE";
                throw new BizException(400, "布尔类型的默认值只能是 TRUE/FALSE: " + v);
            }
            case DATETIME -> {
                String upper = v.toUpperCase(Locale.ROOT);
                if (upper.equals("CURRENT_TIMESTAMP") || upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME"))
                    return upper;
                try {
                    LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    return quote(v);
                } catch (Exception ignore) { /* try datetime */ }
                String dt = v.replace('T', ' ');
                try {
                    java.time.LocalDateTime.parse(dt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    return quote(dt);
                } catch (Exception ignore) { /* try time */ }
                try {
                    LocalTime.parse(v, DateTimeFormatter.ofPattern("HH:mm:ss"));
                    return quote(v);
                } catch (Exception e) {
                    throw new BizException(400, "日期时间类型的默认值须为字面量或 CURRENT_TIMESTAMP/CURRENT_DATE/CURRENT_TIME: " + v);
                }
            }
            case TEXT -> {
                return quote(v);
            }
            default -> throw new BizException(400, "该类型暂不支持设置默认值: " + v);
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
