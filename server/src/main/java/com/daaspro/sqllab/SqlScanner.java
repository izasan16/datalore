package com.daaspro.sqllab;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** SQL 语句静态扫描：注释/字符串剥离、结构判断。所有安全校验基于 normalize 后的文本。 */
public final class SqlScanner {

    private SqlScanner() {}

    /**
     * 剥除注释（行注释 -- 与块注释，块注释支持 PG 嵌套），
     * 字符串字面量（单引号串含两单引号转义、美元引用串）替换为两单引号占位。
     * 结果仅保留语句结构，用于关键字/分号/模式判断；执行时仍用原始 SQL。
     */
    public static String normalize(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        final int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(' ');
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("''");
                continue;
            }
            if (c == '$') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
                if (j < n && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int end = sql.indexOf(tag, j + 1);
                    if (end >= 0) {
                        i = end + tag.length();
                        out.append("''");
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final Pattern FIRST_WORD = Pattern.compile("[a-zA-Z]+");

    /** normalize 后第一个单词（大写）；空文本返回 ""。 */
    public static String firstKeyword(String normalized) {
        var m = FIRST_WORD.matcher(normalized);
        return m.find() ? m.group().toUpperCase() : "";
    }

    /** normalize 后有效的语句分隔分号数（尾随空段的分号不计）。 */
    public static int semicolons(String normalized) {
        int count = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == ';' && !normalized.substring(i + 1).trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static final Pattern TENANT_REF = Pattern.compile("(?i)\\b(tenant_[a-z0-9_]+)\\b");

    /** normalize 后出现的所有 tenant_xxx 引用（小写集合）。 */
    public static Set<String> tenantRefs(String normalized) {
        var set = new HashSet<String>();
        var m = TENANT_REF.matcher(normalized);
        while (m.find()) set.add(m.group(1).toLowerCase());
        return set;
    }
}
