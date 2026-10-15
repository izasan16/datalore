package com.daaspro.datasource.service;

import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.JdbcUrls;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据管理表详情：行分页/新增/删除 + 字段 DDL。
 * 安全要点：schema/table/column 全部严格标识符校验后引用；过滤值一律参数化；列白名单来自实时元数据。
 * 行定位：PG 用 ctid（对齐 DaaS，无需主键）；其他库必须主键，rowId 用 base64(JSON) 编码。
 */
@Service
public class TableDataService {

    private static final Pattern IDENT = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_$]{0,62}$");
    private static final Set<String> OPS = Set.of("eq", "ne", "like", "gt", "gte", "lt", "lte", "isnull", "notnull");
    private static final Set<String> STD_TYPES = Set.of("bigint", "numeric", "text", "timestamp", "date", "boolean");
    private static final int MAX_ROW_IDS = 500;
    private static final int EXPORT_CAP = 10000;

    private final DataSourceService dataSourceService;
    private final ConnectionManager connectionManager;
    private final AesGcmCipher cipher;
    private final ObjectMapper om = new ObjectMapper();

    public TableDataService(DataSourceService dataSourceService, ConnectionManager connectionManager,
                            AesGcmCipher cipher) {
        this.dataSourceService = dataSourceService;
        this.connectionManager = connectionManager;
        this.cipher = cipher;
    }

    public record FilterCond(String col, String op, String value) {}

    // ---------- 行查询 ----------

    public Map<String, Object> rows(Long dsId, String schemaParam, String table, int page, int pageSize,
                                    String filtersJson, String orderBy, String orderDir) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        int size = Math.max(1, Math.min(pageSize <= 0 ? 50 : pageSize, 100));
        int pageNo = Math.max(1, page <= 0 ? 1 : page);

        List<Map<String, String>> cols = dataSourceService.listColumns(dsId, table, schema);
        Set<String> colNames = cols.stream().map(c -> c.get("name")).collect(java.util.stream.Collectors.toSet());
        List<FilterCond> filters = parseFilters(filtersJson, colNames);
        var whereResult = buildWhere(ds, filters, cols);
        String where = whereResult.getKey();
        List<Object> whereBinds = whereResult.getValue();

        String orderSql = "";
        if (orderBy != null && !orderBy.isBlank()) {
            validateIdent(orderBy, "排序字段");
            if (!colNames.contains(orderBy)) throw new BizException(400, "排序字段不在该表中: " + orderBy);
            String dir = "desc".equalsIgnoreCase(orderDir) ? "DESC" : "ASC";
            orderSql = " ORDER BY " + quoteIdent(ds, orderBy) + " " + dir;
        }

        String q = qualified(ds, schema, table);
        boolean pg = "postgresql".equals(ds.getType());
        String selectList = pg ? "ctid AS \"__ctid\", *" : "*";
        String limitSql = limitClause(ds, size, (pageNo - 1) * size, orderSql);

        List<Map<String, String>> pkColumns = primaryKeyColumns(ds, schema, table);
        boolean deletable = pg || !pkColumns.isEmpty();

        try (Connection conn = open(ds)) {
            long total;
            try (var stCount = conn.prepareStatement("SELECT count(*) FROM " + q + where)) {
                for (int i = 0; i < whereBinds.size(); i++) stCount.setObject(i + 1, whereBinds.get(i));
                try (var rs = stCount.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
            }
            List<String> names = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            List<String> rowIds = new ArrayList<>();
            try (var st = conn.prepareStatement("SELECT " + selectList + " FROM " + q + where + orderSql + limitSql)) {
                for (int i = 0; i < whereBinds.size(); i++) st.setObject(i + 1, whereBinds.get(i));
                try (var rs = st.executeQuery()) {
                var md = rs.getMetaData();
                int n = md.getColumnCount();
                int ctidIdx = 0;
                for (int i = 1; i <= n; i++) {
                    String label = md.getColumnLabel(i);
                    if ("__ctid".equals(label)) { ctidIdx = i; continue; }
                    names.add(label);
                }
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    Map<String, String> byName = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        if (i == ctidIdx) continue;
                        String v = rs.getString(i);
                        row.add(v);
                        byName.put(md.getColumnLabel(i), v);
                    }
                    rows.add(row);
                    rowIds.add(encodeRowId(ds, schema, table, byName, ctidIdx > 0 ? rs.getString(ctidIdx) : null, pkColumns));
                }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", cols);
            out.put("rows", rows);
            out.put("rowIds", rowIds);
            out.put("total", total);
            out.put("page", pageNo);
            out.put("pageSize", size);
            out.put("deletable", deletable);
            if (!deletable) out.put("deleteReason", "该表无主键，无法定位行");
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取表数据失败: " + DataSourceService.rootMessage(e));
        }
    }

    /** 导出用：按当前筛选取最多 1 万行（复用 rows 逻辑，pageSize 封顶）。 */
    public Map<String, Object> exportRows(Long dsId, String schema, String table, String filtersJson) {
        return rows(dsId, schema, table, 1, EXPORT_CAP, filtersJson, null, null);
    }

    // ---------- 新增行 ----------

    public void insertRow(Long dsId, String schemaParam, String table, Map<String, String> values) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        if (values == null || values.isEmpty()) throw new BizException(400, "请至少填写一列的值");
        List<Map<String, String>> cols = dataSourceService.listColumns(dsId, table, schema);
        Map<String, String> typeByName = new LinkedHashMap<>();
        cols.forEach(c -> typeByName.put(c.get("name"), c.get("type")));

        List<String> useCols = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (var e : values.entrySet()) {
            String col = e.getKey();
            if (!typeByName.containsKey(col)) throw new BizException(400, "列不属于该表: " + col);
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue; // 空值 → 不写该列，走表默认值/NULL
            useCols.add(col);
            binds.add(convertValue(typeByName.get(col), col, v));
        }
        if (useCols.isEmpty()) throw new BizException(400, "所有值均为空，未执行插入");
        String colList = useCols.stream().map(c -> quoteIdent(ds, c)).reduce((a, b) -> a + ", " + b).orElse("");
        String ph = String.join(", ", java.util.Collections.nCopies(useCols.size(), "?"));
        try (Connection conn = open(ds);
             var st = conn.prepareStatement("INSERT INTO " + qualified(ds, schema, table) + " (" + colList + ") VALUES (" + ph + ")")) {
            for (int i = 0; i < binds.size(); i++) st.setObject(i + 1, binds.get(i));
            st.executeUpdate();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "插入失败: " + DataSourceService.rootMessage(e));
        }
    }

    // ---------- 删除行 ----------

    public void deleteRows(Long dsId, String schemaParam, String table, List<String> rowIds) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        if (rowIds == null || rowIds.isEmpty()) throw new BizException(400, "请先勾选要删除的行");
        if (rowIds.size() > MAX_ROW_IDS) throw new BizException(400, "单次最多删除 " + MAX_ROW_IDS + " 行");

        boolean pg = "postgresql".equals(ds.getType());
        List<Map<String, String>> pkColumns = pg ? List.of() : primaryKeyColumns(ds, schema, table);
        if (!pg && pkColumns.isEmpty()) throw new BizException(400, "该表无主键，无法定位行");

        String q = qualified(ds, schema, table);
        try (Connection conn = open(ds)) {
            int total = 0;
            for (String encoded : rowIds) {
                Map<String, String> id = decodeRowId(encoded);
                try (var st = buildDelete(conn, ds, q, id, pkColumns, pg)) {
                    total += st.executeUpdate();
                }
            }
            if (total != rowIds.size()) {
                throw new BizException(400, "部分行删除失败（可能已被其他操作删除），实际删除 " + total + " 行");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "删除失败: " + DataSourceService.rootMessage(e));
        }
    }

    private PreparedStatement buildDelete(Connection conn, DataSourceEntity ds, String q,
                                          Map<String, String> id, List<Map<String, String>> pkColumns,
                                          boolean pg) throws Exception {
        PreparedStatement st;
        if (pg) {
            String ctid = id.get("ctid");
            if (ctid == null || !ctid.matches("\\(\\d+,\\d+\\)")) throw new BizException(400, "行标识非法");
            st = conn.prepareStatement("DELETE FROM " + q + " WHERE ctid = ?");
            st.setString(1, ctid);
            return st;
        }
        List<String> conds = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        Map<String, String> pkTypes = new LinkedHashMap<>();
        pkColumns.forEach(p -> pkTypes.put(p.get("name"), p.get("type")));
        for (String pk : pkTypes.keySet()) {
            String v = id.get(pk);
            if (v == null) throw new BizException(400, "行标识缺少主键值: " + pk);
            conds.add(quoteIdent(ds, pk) + " = ?");
            binds.add(convertValue(pkTypes.get(pk), pk, v));
        }
        st = conn.prepareStatement("DELETE FROM " + q + " WHERE " + String.join(" AND ", conds));
        for (int i = 0; i < binds.size(); i++) st.setObject(i + 1, binds.get(i));
        return st;
    }

    // ---------- 字段 DDL ----------

    public void columnDdl(Long dsId, String schemaParam, String table, String action,
                          String column, String newColumn, String type) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        String dsType = ds.getType();
        String q = qualified(ds, schema, table);
        String sql = switch (action == null ? "" : action) {
            case "addColumn" -> {
                validateIdent(column, "列名");
                String t = dialectType(dsType, type);
                yield switch (dsType) {
                    case "sqlserver" -> "ALTER TABLE " + q + " ADD " + quoteIdent(ds, column) + " " + t;
                    case "oracle" -> "ALTER TABLE " + q + " ADD (" + quoteIdent(ds, column) + " " + t + ")";
                    default -> "ALTER TABLE " + q + " ADD COLUMN " + quoteIdent(ds, column) + " " + t;
                };
            }
            case "dropColumn" -> {
                validateIdent(column, "列名");
                List<Map<String, String>> pk = primaryKeyColumns(ds, schema, table);
                boolean isPk = pk.stream().anyMatch(p -> p.get("name").equalsIgnoreCase(column));
                if (isPk) throw new BizException(400, "主键列不能删除: " + column);
                yield "ALTER TABLE " + q + " DROP COLUMN " + quoteIdent(ds, column);
            }
            case "renameColumn" -> {
                validateIdent(column, "列名");
                validateIdent(newColumn, "新列名");
                yield switch (dsType) {
                    case "sqlserver" -> "EXEC sp_rename '" + esc(schema) + "." + esc(table) + "."
                            + esc(column) + "', '" + esc(newColumn) + "', 'COLUMN'";
                    default -> "ALTER TABLE " + q + " RENAME COLUMN " + quoteIdent(ds, column)
                            + " TO " + quoteIdent(ds, newColumn);
                };
            }
            case "alterType" -> {
                validateIdent(column, "列名");
                String t = dialectType(dsType, type);
                yield switch (dsType) {
                    case "postgresql" -> "ALTER TABLE " + q + " ALTER COLUMN " + quoteIdent(ds, column)
                            + " TYPE " + t + " USING " + quoteIdent(ds, column) + "::" + t;
                    case "mysql" -> "ALTER TABLE " + q + " MODIFY COLUMN " + quoteIdent(ds, column) + " " + t;
                    case "sqlserver" -> "ALTER TABLE " + q + " ALTER COLUMN " + quoteIdent(ds, column) + " " + t;
                    case "oracle" -> "ALTER TABLE " + q + " MODIFY (" + quoteIdent(ds, column) + " " + t + ")";
                    default -> throw new BizException(400, "不支持的数据源类型: " + dsType);
                };
            }
            default -> throw new BizException(400, "不支持的字段操作: " + action);
        };
        try (Connection conn = open(ds);
             var st = conn.createStatement()) {
            st.execute(sql);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "字段修改失败: " + DataSourceService.rootMessage(e));
        }
    }

    // ---------- 批量字段编辑（字段 Tab 整表编辑模式） ----------

    public record ColumnChange(String op, String column, String name, String base, Integer length,
                               Integer precision, Integer scale, Boolean nullable,
                               String defaultValue, String comment) {}

    /** 批量字段变更：preview=true 只返回将执行的语句；false 按序执行，失败标明第几条并透传原生错误。 */
    public List<String> batchColumnDdl(Long dsId, String schemaParam, String table,
                                       List<ColumnChange> changes, boolean preview) {
        DataSourceEntity ds = dataSourceService.getOwned(dsId);
        String schema = schema(ds, schemaParam);
        validateIdent(schema, "schema");
        validateIdent(table, "表名");
        String dsType = ds.getType();
        Map<String, ColumnDdlBuilder.Col> meta = new HashMap<>();
        for (Map<String, String> c : dataSourceService.listColumns(dsId, table, schema)) {
            meta.put(c.get("name").toLowerCase(Locale.ROOT),
                    new ColumnDdlBuilder.Col(c.get("name"), c.get("type"),
                            "YES".equalsIgnoreCase(c.get("nullable")),
                            c.get("default"), c.get("comment")));
        }
        List<String> statements = new ArrayList<>();
        for (ColumnChange ch : changes) {
            if (ch == null) continue;
            boolean add = "add".equals(ch.op());
            if (!add && !"update".equals(ch.op())) throw new BizException(400, "不支持的字段操作: " + ch.op());
            if (ch.name() == null || ch.name().isBlank()) throw new BizException(400, "列名不能为空");
            String targetName = ch.name().trim();
            validateIdent(targetName, "列名");
            ColumnDdlBuilder.Col current = null;
            if (!add) {
                validateIdent(ch.column(), "列名");
                current = meta.get(ch.column().toLowerCase(Locale.ROOT));
                if (current == null) throw new BizException(400, "列不存在: " + ch.column());
            }
            var target = new ColumnDdlBuilder.Target(ch.op(), targetName, ch.base(), ch.length(),
                    ch.precision(), ch.scale(), ch.nullable(), ch.defaultValue(), ch.comment());
            statements.addAll(ColumnDdlBuilder.build(dsType, schema, table, current, target));
        }
        if (preview) return statements;
        try (Connection conn = open(ds)) {
            int i = 0;
            for (String sql : statements) {
                i++;
                try (var st = conn.createStatement()) {
                    st.execute(sql);
                } catch (Exception e) {
                    if (e instanceof BizException b) throw b;
                    throw new BizException(400, "第 " + i + " 条语句执行失败: " + sql + " | "
                            + DataSourceService.rootMessage(e));
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "字段修改失败: " + DataSourceService.rootMessage(e));
        }
        return statements;
    }

    // ---------- 内部工具 ----------

    private String schema(DataSourceEntity ds, String schemaParam) {
        if (schemaParam != null && !schemaParam.isBlank()) return schemaParam.trim();
        return switch (ds.getType()) {
            case "postgresql" -> ds.getSchemaName() == null ? "public" : ds.getSchemaName();
            case "mysql" -> ds.getDatabaseName() == null ? "" : ds.getDatabaseName();
            case "sqlserver" -> "dbo";
            case "oracle" -> ds.getUsername() == null ? "" : ds.getUsername().toUpperCase();
            default -> "public";
        };
    }

    private List<FilterCond> parseFilters(String filtersJson, Set<String> colNames) {
        if (filtersJson == null || filtersJson.isBlank()) return List.of();
        List<Map<String, String>> raw;
        try {
            raw = om.readValue(filtersJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BizException(400, "筛选条件格式非法");
        }
        List<FilterCond> out = new ArrayList<>();
        for (Map<String, String> f : raw) {
            String col = f.get("col"), op = f.get("op");
            if (col == null || !colNames.contains(col)) throw new BizException(400, "筛选列不属于该表: " + col);
            if (op == null || !OPS.contains(op)) throw new BizException(400, "不支持的筛选操作符: " + op);
            out.add(new FilterCond(col, op, f.get("value")));
        }
        return out;
    }

    private Map.Entry<String, List<Object>> buildWhere(DataSourceEntity ds, List<FilterCond> filters,
                                                       List<Map<String, String>> cols) {
        if (filters.isEmpty()) return Map.entry("", List.of());
        Map<String, String> typeByName = new LinkedHashMap<>();
        cols.forEach(c -> typeByName.put(c.get("name"), c.get("type")));
        StringBuilder sb = new StringBuilder(" WHERE ");
        List<Object> binds = new ArrayList<>();
        boolean first = true;
        for (FilterCond f : filters) {
            if (!first) sb.append(" AND ");
            first = false;
            String col = f.col(), op = f.op();
            switch (op) {
                case "isnull" -> sb.append(quoteIdent(ds, col)).append(" IS NULL");
                case "notnull" -> sb.append(quoteIdent(ds, col)).append(" IS NOT NULL");
                case "like" -> {
                    String qcol = quoteIdent(ds, col);
                    sb.append(switch (ds.getType()) {
                        case "postgresql" -> qcol + "::text LIKE ?";
                        case "sqlserver" -> "CAST(" + qcol + " AS NVARCHAR(4000)) LIKE ?";
                        case "oracle" -> "TO_CHAR(" + qcol + ") LIKE ?";
                        default -> qcol + " LIKE ?";
                    });
                    binds.add("%" + (f.value() == null ? "" : f.value()) + "%");
                }
                default -> {
                    sb.append(quoteIdent(ds, col)).append(switch (op) {
                    case "ne" -> " <> ?";
                    case "gt" -> " > ?";
                    case "gte" -> " >= ?";
                    case "lt" -> " < ?";
                    case "lte" -> " <= ?";
                    default -> " = ?";
                    });
                    binds.add(convertValue(typeByName.get(col), col, f.value() == null ? "" : f.value()));
                }
            }
        }
        return Map.entry(sb.toString(), binds);
    }

    private String limitClause(DataSourceEntity ds, int size, int offset, String orderSql) {
        return switch (ds.getType()) {
            case "sqlserver" -> (orderSql.isEmpty() ? " ORDER BY (SELECT NULL)" : "")
                    + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
            case "oracle" -> " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
            default -> " LIMIT " + size + " OFFSET " + offset;
        };
    }

    private List<Map<String, String>> primaryKeyColumns(DataSourceEntity ds, String schema, String table) {
        String sql = JdbcUrls.of(ds.getType()).primaryKeySql(schema, table);
        try (Connection conn = open(ds);
             var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            List<Map<String, String>> all = dataSourceService.listColumns(ds.getId(), table, schema);
            Map<String, String> typeByName = new LinkedHashMap<>();
            all.forEach(c -> typeByName.put(c.get("name"), c.get("type")));
            List<Map<String, String>> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                out.add(Map.of("name", name, "type", typeByName.getOrDefault(name, "text")));
            }
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取主键失败: " + DataSourceService.rootMessage(e));
        }
    }

    private String encodeRowId(DataSourceEntity ds, String schema, String table,
                               Map<String, String> row, String ctid, List<Map<String, String>> pkColumns) {
        try {
            Map<String, String> id = new LinkedHashMap<>();
            if (ctid != null) {
                id.put("ctid", ctid);
            } else {
                for (Map<String, String> pk : pkColumns) id.put(pk.get("name"), row.get(pk.get("name")));
            }
            return Base64.getEncoder().encodeToString(
                    om.writeValueAsString(id).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BizException(500, "行标识编码失败");
        }
    }

    private Map<String, String> decodeRowId(String encoded) {
        try {
            return om.readValue(Base64.getDecoder().decode(encoded), new TypeReference<>() {});
        } catch (Exception e) {
            throw new BizException(400, "行标识非法");
        }
    }

    /** 按列类型把字符串值转换为 JDBC 绑定对象；解析失败给出明确报错。 */
    private Object convertValue(String type, String col, String value) {
        String t = type == null ? "" : type.toLowerCase();
        try {
            if (t.contains("bool")) return Boolean.parseBoolean(value.trim());
            if (t.contains("bigint") || t.contains("int8") || t.contains(" int") || t.contains("integer"))
                return Long.valueOf(value.trim());
            if (t.contains("numeric") || t.contains("decimal") || t.contains("number")) return new BigDecimal(value.trim());
            if (t.contains("timestamp") || t.contains("datetime"))
                return Timestamp.valueOf(value.trim().replace('T', ' '));
            if (t.equals("date")) return Date.valueOf(value.trim());
            return value;
        } catch (Exception e) {
            throw new BizException(400, "字段 " + col + " 需要 " + type + " 类型的值: " + value);
        }
    }

    /** 平台标准类型 → 数据源方言类型。 */
    private String dialectType(String dsType, String std) {
        if (std == null || !STD_TYPES.contains(std)) throw new BizException(400, "不支持的字段类型: " + std);
        return switch (std) {
            case "bigint" -> "BIGINT";
            case "numeric" -> switch (dsType) {
                case "postgresql" -> "NUMERIC";
                case "mysql", "sqlserver" -> "DECIMAL(18,4)";
                case "oracle" -> "NUMBER(18,4)";
                default -> throw new BizException(400, "不支持的数据源类型: " + dsType);
            };
            case "text" -> switch (dsType) {
                case "postgresql", "mysql" -> "TEXT";
                case "sqlserver" -> "NVARCHAR(MAX)";
                case "oracle" -> "CLOB";
                default -> throw new BizException(400, "不支持的数据源类型: " + dsType);
            };
            case "timestamp" -> switch (dsType) {
                case "mysql" -> "DATETIME";
                case "sqlserver" -> "DATETIME2";
                default -> "TIMESTAMP";
            };
            case "date" -> "DATE";
            case "boolean" -> switch (dsType) {
                case "postgresql" -> "BOOLEAN";
                case "mysql" -> "TINYINT(1)";
                case "sqlserver" -> "BIT";
                case "oracle" -> "NUMBER(1)";
                default -> throw new BizException(400, "不支持的数据源类型: " + dsType);
            };
            default -> throw new BizException(400, "不支持的字段类型: " + std);
        };
    }

    private void validateIdent(String value, String label) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new BizException(400, label + "非法: " + value);
        }
    }

    private String quoteIdent(DataSourceEntity ds, String ident) {
        if ("mysql".equals(ds.getType())) return "`" + ident.replace("`", "``") + "`";
        if ("sqlserver".equals(ds.getType())) return "[" + ident.replace("]", "]]") + "]";
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** SQL Server sp_rename 参数转义（单引号翻倍）。 */
    private String esc(String ident) {
        return ident.replace("'", "''");
    }

    private String qualified(DataSourceEntity ds, String schema, String ident) {
        return quoteIdent(ds, schema) + "." + quoteIdent(ds, ident);
    }

    private Connection open(DataSourceEntity ds) {
        try {
            return connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc())).getConnection();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "获取连接失败: " + DataSourceService.rootMessage(e));
        }
    }
}
