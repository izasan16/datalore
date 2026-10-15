package com.daaspro.datasource.service;

import com.daaspro.common.exception.BizException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 字段批量 DDL 语句生成器（纯函数，便于单测）。
 * 每个变更生成 1-N 条 ALTER 语句；PG 拆分语句，MySQL/Oracle 合并 MODIFY，SQL Server 仅名称/类型/可空。
 */
public final class ColumnDdlBuilder {

    /** 当前列（来自数据库元数据）。type 为原生类型串（可带参数），defaultExpr 为默认值表达式原文。 */
    public record Col(String name, String type, boolean nullable, String defaultExpr, String comment) {}

    /** 目标状态。base 为 null 或 __keep__ 表示保持现有类型；op = add | update。 */
    public record Target(String op, String name, String base, Integer length, Integer precision, Integer scale,
                         Boolean nullable, String defaultValue, String comment) {}

    private ColumnDdlBuilder() {}

    public static List<String> build(String dsType, String schema, String table, Col current, Target t) {
        boolean add = "add".equals(t.op());
        List<String> out = new ArrayList<>();
        String q = qualified(dsType, schema, table);
        String colQ = quoteIdent(dsType, t.name());

        // SQL Server：默认值（约束生命周期）与注释（扩展属性）暂不支持
        if ("sqlserver".equals(dsType)) {
            boolean defaultChanged = add ? t.defaultValue() != null && !t.defaultValue().isBlank()
                    : (t.defaultValue() == null ? false
                       : defaultChanged(current.defaultExpr(), literalOrNull(dsType, current, t)));
            boolean commentChanged = add ? !isBlank(t.comment())
                    : !strEq(t.comment(), current.comment());
            if (defaultChanged) throw new BizException(400, "SQL Server 暂不支持修改默认值");
            if (commentChanged) throw new BizException(400, "SQL Server 暂不支持修改列注释");
        }

        String typeStr = keepType(t.base()) ? current.type()
                : ColumnTypes.build(dsType, t.base(), t.length(), t.precision(), t.scale());
        String newDefault = literalOrNull(dsType, current, t);

        boolean typeChanged = !add && !typeStr.equalsIgnoreCase(current.type());
        boolean nullable = t.nullable() == null ? current.nullable() : t.nullable();
        boolean nullableChanged = !add && nullable != current.nullable();
        // defaultValue == null 表示用户未修改（表达式默认值原样保留）；空串表示清除
        boolean defaultTouched = t.defaultValue() != null;
        boolean defaultChanged = !defaultTouched ? false
                : (add ? newDefault != null : defaultChanged(current.defaultExpr(), newDefault));
        boolean commentChanged = !add && !strEq(t.comment(), current.comment());
        boolean renamed = !add && !t.name().equalsIgnoreCase(current.name());

        // 未重命名且无任何变更：无语句
        if (!add && !renamed && !typeChanged && !nullableChanged && !defaultChanged && !commentChanged) {
            return out;
        }

        // 1. 重命名（先执行，后续语句使用新名）
        if (renamed) {
            out.add(switch (dsType) {
                case "sqlserver" -> "EXEC sp_rename '" + esc(schema) + "." + esc(table) + "."
                        + esc(current.name()) + "', '" + esc(t.name()) + "', 'COLUMN'";
                default -> "ALTER TABLE " + q + " RENAME COLUMN " + quoteIdent(dsType, current.name())
                        + " TO " + colQ;
            });
        }

        if (add) {
            String notNull = nullable ? "" : " NOT NULL";
            String def = newDefault == null ? "" : " DEFAULT " + newDefault;
            out.add(switch (dsType) {
                case "mysql" -> "ALTER TABLE " + q + " ADD COLUMN " + colQ + " " + typeStr + notNull + def
                        + (isBlank(t.comment()) ? "" : " COMMENT " + quote(t.comment().trim()));
                case "sqlserver" -> "ALTER TABLE " + q + " ADD " + colQ + " " + typeStr + notNull;
                case "oracle" -> "ALTER TABLE " + q + " ADD (" + colQ + " " + typeStr + def + notNull + ")";
                default -> "ALTER TABLE " + q + " ADD COLUMN " + colQ + " " + typeStr + def + notNull;
            });
            if (("postgresql".equals(dsType) || "oracle".equals(dsType)) && !isBlank(t.comment())) {
                out.add("COMMENT ON COLUMN " + q + "." + colQ + " IS " + quote(t.comment().trim()));
            }
            return out;
        }

        switch (dsType) {
            case "postgresql" -> {
                if (typeChanged) {
                    out.add("ALTER TABLE " + q + " ALTER COLUMN " + colQ + " TYPE " + typeStr
                            + " USING " + colQ + "::" + typeStr);
                }
                if (nullableChanged) {
                    out.add("ALTER TABLE " + q + " ALTER COLUMN " + colQ
                            + (!nullable ? " SET" : " DROP") + " NOT NULL");
                }
                if (defaultChanged) {
                    out.add("ALTER TABLE " + q + " ALTER COLUMN " + colQ
                            + (newDefault == null ? " DROP DEFAULT" : " SET DEFAULT " + newDefault));
                }
                if (commentChanged) {
                    out.add("COMMENT ON COLUMN " + q + "." + colQ + " IS "
                            + (isBlank(t.comment()) ? "NULL" : quote(t.comment().trim())));
                }
            }
            case "mysql" -> {
                if (typeChanged || nullableChanged || defaultChanged || commentChanged) {
                    String def;
                    if (defaultChanged) {
                        def = newDefault == null ? "" : " DEFAULT " + newDefault;
                    } else {
                        def = current.defaultExpr() == null ? "" : " DEFAULT " + current.defaultExpr().trim();
                    }
                    String cmt = isBlank(t.comment()) ? (current.comment() == null ? "" : current.comment())
                            : t.comment().trim();
                    out.add("ALTER TABLE " + q + " MODIFY COLUMN " + colQ + " " + typeStr
                            + (nullable ? " NULL" : " NOT NULL") + def
                            + (isBlank(cmt) ? "" : " COMMENT " + quote(cmt)));
                }
            }
            case "oracle" -> {
                if (typeChanged || nullableChanged || defaultChanged) {
                    String def;
                    if (defaultChanged) {
                        def = newDefault == null ? "" : " DEFAULT " + newDefault;
                    } else {
                        def = current.defaultExpr() == null ? "" : " DEFAULT " + current.defaultExpr().trim();
                    }
                    out.add("ALTER TABLE " + q + " MODIFY (" + colQ + " " + typeStr + def
                            + (nullable ? " NULL" : " NOT NULL") + ")");
                }
                if (commentChanged) {
                    out.add("COMMENT ON COLUMN " + q + "." + colQ + " IS "
                            + (isBlank(t.comment()) ? "NULL" : quote(t.comment().trim())));
                }
            }
            case "sqlserver" -> {
                if (typeChanged || nullableChanged) {
                    out.add("ALTER TABLE " + q + " ALTER COLUMN " + colQ + " " + typeStr
                            + (nullable ? " NULL" : " NOT NULL"));
                }
            }
            default -> throw new BizException(400, "不支持的数据源类型: " + dsType);
        }
        return out;
    }

    /** 目标默认值 → 字面量/表达式；空串返回 null 表示清除（null 在上游已表示未修改）。保持现有类型时仅允许保持现值，防止注入。 */
    private static String literalOrNull(String dsType, Col current, Target t) {
        if (t.defaultValue() == null) return current == null ? null : current.defaultExpr();
        if (t.defaultValue().isBlank()) return null;
        if (keepType(t.base())) {
            if (current.defaultExpr() != null && normalizeExpr(t.defaultValue())
                    .equals(normalizeExpr(current.defaultExpr()))) return current.defaultExpr().trim();
            throw new BizException(400, "该列类型不在类型目录中，暂不支持修改默认值: " + t.defaultValue());
        }
        return ColumnTypes.defaultLiteral(ColumnTypes.family(dsType, t.base()), t.defaultValue());
    }

    private static boolean keepType(String base) {
        return base == null || base.isBlank() || "__keep__".equalsIgnoreCase(base);
    }

    static boolean defaultChanged(String currentExpr, String newLit) {
        if (newLit == null) return currentExpr != null && !currentExpr.isBlank();
        if (currentExpr == null || currentExpr.isBlank()) return true;
        return !normalizeExpr(currentExpr).equals(normalizeExpr(newLit));
    }

    /** 归一化默认值表达式用于变更比较：去掉 PG cast、去引号、大写。 */
    static String normalizeExpr(String s) {
        if (s == null) return "";
        String v = s.trim();
        int idx = v.indexOf("::");
        if (idx >= 0) v = v.substring(0, idx).trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
        return v.toUpperCase(Locale.ROOT);
    }

    private static boolean strEq(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }

    /** MySQL 反引号，SQL Server 方括号，其余双引号。 */
    static String quoteIdent(String dsType, String ident) {
        if ("mysql".equals(dsType)) return "\u0060" + ident.replace("\u0060", "\u0060\u0060") + "\u0060";
        if ("sqlserver".equals(dsType)) return "[" + ident.replace("]", "]]") + "]";
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    static String qualified(String dsType, String schema, String table) {
        return quoteIdent(dsType, schema) + "." + quoteIdent(dsType, table);
    }
}
