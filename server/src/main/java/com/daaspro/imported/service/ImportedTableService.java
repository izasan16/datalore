package com.daaspro.imported.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.imported.entity.ImportedTableEntity;
import com.daaspro.imported.mapper.ImportedTableMapper;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.ConnectionManager;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class ImportedTableService {

    private static final Set<String> ALLOWED_TYPES = Set.of("bigint", "numeric", "text", "timestamp", "date", "boolean");
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private final ImportedTableMapper mapper;
    private final DataSourceService dataSourceService;
    private final ConnectionManager connectionManager;
    private final AesGcmCipher cipher;
    private final TabularParser parser;
    private final MinioClient minio;
    private final String bucket;
    private final ObjectMapper om = new ObjectMapper();

    public ImportedTableService(ImportedTableMapper mapper, DataSourceService dataSourceService,
                          ConnectionManager connectionManager, AesGcmCipher cipher, TabularParser parser,
                          MinioClient minio, @Value("${daaspro.minio.bucket}") String bucket) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
        this.connectionManager = connectionManager;
        this.cipher = cipher;
        this.parser = parser;
        this.minio = minio;
        this.bucket = bucket;
    }

    public record UploadResult(String uploadId, String fileName, List<String> columns,
                               List<String> types, List<List<String>> previewRows, long rowCount) {}

    public UploadResult upload(MultipartFile file) {
        if (file.isEmpty()) throw new BizException(400, "文件为空");
        if (file.getSize() > MAX_SIZE) throw new BizException(400, "文件超过 20MB 上限");
        String fileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        try (var in = file.getInputStream()) {
            var parsed = parser.parse(fileName, in);
            String uploadId = "staging/" + TenantContext.requireTenantId() + "/" + UUID.randomUUID() + ".csv";
            byte[] csv = parsed.normalizedCsv();
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(uploadId)
                    .stream(new ByteArrayInputStream(csv), csv.length, -1)
                    .contentType("text/csv").build());
            return new UploadResult(uploadId, fileName, parsed.columns(), parsed.types(),
                    parsed.previewRows(), parsed.rowCount());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "上传失败: " + e.getMessage());
        }
    }

    public record ConfirmReq(String uploadId, String name, String fileName,
                             List<String> columns, List<String> types, Long datasourceId,
                             String schemaName, String existingTable) {}

    /** 确认导入。目标数据源必填；existingTable 非空 → 向已有表追加（校验列名完全一致）。 */
    public ImportedTableEntity confirm(ConfirmReq req) {
        if (req.columns() == null || req.columns().isEmpty() || req.types() == null
                || req.columns().size() != req.types().size()) {
            throw new BizException(400, "列定义不完整");
        }
        if (req.types().stream().anyMatch(t -> !ALLOWED_TYPES.contains(t))) {
            throw new BizException(400, "列类型非法");
        }
        boolean append0 = req.existingTable() != null && !req.existingTable().isBlank();
        if (!append0 && (req.name() == null || req.name().isBlank())) {
            throw new BizException(400, "名称不能为空");
        }
        if (!append0 && !req.name().trim().matches("^[\\p{L}_][\\p{L}\\p{N}_]{0,62}$")) {
            throw new BizException(400, "表名只能包含字母、数字、下划线，且以字母或下划线开头");
        }
        String schema = req.schemaName() == null || req.schemaName().isBlank() ? null : req.schemaName().trim();

        DataSourceEntity target;
        if (req.datasourceId == null) {
            throw new BizException(400, "请选择目标数据源");
        } else {
            target = dataSourceService.getOwned(req.datasourceId());
        }
        boolean append = req.existingTable() != null && !req.existingTable().isBlank();

        byte[] csv = download(req.uploadId());
        String table;
        long copyCount;
        List<String> columns = req.columns();
        List<String> types = req.types();

        if (append) {
            table = req.existingTable();
            // 校验目标表存在且列名一致（列名集合与顺序都要求一致，导入语义最简单可靠）
            List<Map<String, String>> actual = tableColumns(target, table, schema);
            if (actual.isEmpty()) throw new BizException(400, "目标表不存在: " + table);
            List<String> actualNames = actual.stream().map(c -> c.get("name")).toList();
            if (!actualNames.equals(columns)) {
                throw new BizException(400, "列与目标表不一致。目标表列: " + actualNames);
            }
            copyCount = insertRows(target, schema, table, columns, csv, true);
            // 追加到已有导入表：只更新其行数，不新建记录（否则删除任一条会连坐共享物理表）
            ImportedTableEntity existing = mapper.selectList(new LambdaQueryWrapper<ImportedTableEntity>()
                            .eq(ImportedTableEntity::getTenantId, TenantContext.requireTenantId())
                            .eq(ImportedTableEntity::getTableName, table))
                    .stream().findFirst().orElse(null);
            if (existing != null) {
                existing.setRowCount(countRows(target, schema, table));
                mapper.updateById(existing);
                return existing;
            }
            // 目标表不是本平台数据集：拒绝，避免一张表挂两条记录
            throw new BizException(400, "目标表不是本平台导入的表，无法以追加方式管理");
        }

        table = req.name().trim();
        List<Map<String, String>> existingPhysical = tableColumns(target, table, schema);
        if (!existingPhysical.isEmpty()) throw new BizException(400, "表已存在: " + table);
        copyCount = createTableAndInsert(target, schema, table, columns, types, csv);

        ImportedTableEntity rec = new ImportedTableEntity();
        rec.setTenantId(TenantContext.requireTenantId());
        rec.setName(table);
        rec.setTableName(table);
        rec.setSchemaName(schema);
        rec.setDatasourceId(target.getId());
        rec.setColumnsDef(toColumnsJson(columns, types));
        rec.setFileName(req.fileName());
        rec.setRowCount(copyCount);
        mapper.insert(rec);
        return rec;
    }

    /** 建表并批量 INSERT 导入，返回导入行数。 */
    private long createTableAndInsert(DataSourceEntity ds, String schema, String table,
                                    List<String> columns, List<String> types, byte[] csv) {
        String colDefs = IntStream.range(0, columns.size())
                .mapToObj(i -> quoteIdent(ds, columns.get(i)) + " " + types.get(i))
                .reduce((a, b) -> a + ", " + b).orElse("");
        String ddl = "CREATE TABLE " + qualified(ds, schema, table) + " (" + colDefs + ")";
        try (Connection conn = openConnection(ds)) {
            try (var st = conn.createStatement()) {
                st.execute(ddl);
            }
            return insertRows(ds, schema, table, columns, csv, true);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "导入失败: " + DataSourceService.rootMessage(e));
        }
    }

    private long insertRows(DataSourceEntity ds, String schema, String table, List<String> columns, byte[] csv, boolean header) {
        String colList = columns.stream().map(c -> quoteIdent(ds, c)).reduce((a, b) -> a + "," + b).orElse("");
        String placeholders = "(" + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        String insertSql = "INSERT INTO " + qualified(ds, schema, table) + " (" + colList + ") VALUES " + placeholders;
        try (Connection conn = openConnection(ds);
             var reader = CSVFormat.DEFAULT.parse(new java.io.InputStreamReader(
                     new ByteArrayInputStream(csv), StandardCharsets.UTF_8))) {
            long total = 0;
            boolean first = true;
            int batch = 0;
            try (PreparedStatement st = conn.prepareStatement(insertSql)) {
                for (CSVRecord record : reader) {
                    if (first && header) { first = false; continue; }
                    first = false;
                    if (record.size() != columns.size()) {
                        throw new BizException(400, "第 " + (total + 1) + " 行列数与定义不一致");
                    }
                    // 与旧 COPY 的 NULL '' 语义一致：空字符串导入为 NULL，避免数值/日期列类型错误
                    for (int i = 0; i < columns.size(); i++) {
                        String v = record.get(i);
                        st.setString(i + 1, v == null || v.isEmpty() ? null : v);
                    }
                    st.addBatch();
                    if (++batch >= 1000) { total += java.util.Arrays.stream(st.executeBatch()).sum(); batch = 0; }
                }
                if (batch > 0) total += java.util.Arrays.stream(st.executeBatch()).sum();
            }
            return total;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "导入失败: " + DataSourceService.rootMessage(e));
        }
    }

    private Connection openConnection(DataSourceEntity ds) {
        try {
            return connectionManager.pool(ds, cipher.decrypt(ds.getPasswordEnc())).getConnection();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "获取连接失败: " + DataSourceService.rootMessage(e));
        }
    }

    /** 读取目标表列结构（按数据源类型）。 */
    private List<Map<String, String>> tableColumns(DataSourceEntity ds, String table, String schema) {
        return dataSourceService.listColumns(ds.getId(), table, schema);
    }

    /** 表实际行数（追加后刷新数据集计数用）。 */
    private long countRows(DataSourceEntity ds, String schema, String table) {
        try (Connection conn = openConnection(ds);
             var st = conn.prepareStatement("SELECT count(*) FROM " + qualified(ds, schema, table));
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            throw new BizException(400, "统计行数失败: " + DataSourceService.rootMessage(e));
        }
    }

    public List<Map<String, Object>> list() {
        return mapper.selectList(new LambdaQueryWrapper<ImportedTableEntity>()
                .eq(ImportedTableEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(ImportedTableEntity::getId)).stream()
                .map(d -> Map.<String, Object>of("id", d.getId(), "name", d.getName(),
                        "tableName", d.getTableName(), "schemaName", d.getSchemaName() == null ? "" : d.getSchemaName(),
                        "datasourceId", d.getDatasourceId(), "rowCount", d.getRowCount(),
                        "fileName", d.getFileName() == null ? "" : d.getFileName(),
                        "createdAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString()))
                .toList();
    }

    public record Preview(List<Map<String, String>> columns, List<List<String>> rows) {}

    public Preview preview(Long id) {
        ImportedTableEntity d = getOwned(id);
        List<Map<String, String>> cols = parseColumns(d.getColumnsDef());
        DataSourceEntity ds = dataSourceService.getOwned(d.getDatasourceId());
        String sql = "SELECT * FROM " + qualified(ds, d.getSchemaName(), d.getTableName()) + " LIMIT 20";
        try (Connection conn = openConnection(ds);
             var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (var c : cols) row.add(rs.getString(c.get("name")));
                rows.add(row);
            }
            return new Preview(cols, rows);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "预览失败: " + DataSourceService.rootMessage(e));
        }
    }

    public void delete(Long id) {
        ImportedTableEntity d = getOwned(id);
        try {
            DataSourceEntity ds = dataSourceService.getOwned(d.getDatasourceId());
            try (Connection conn = openConnection(ds);
                 var st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + qualified(ds, d.getSchemaName(), d.getTableName()));
            }
        } catch (BizException e) {
            if (!"数据源不存在".equals(e.getMessage())) throw e;
            // 数据源已被删除：只清理数据集记录
        } catch (Exception e) {
            throw new BizException(400, "删除失败: " + DataSourceService.rootMessage(e));
        }
        mapper.deleteById(id);
    }

    private ImportedTableEntity getOwned(Long id) {
        ImportedTableEntity d = mapper.selectById(id);
        if (d == null || !d.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "导入记录不存在");
        }
        return d;
    }

    private byte[] download(String uploadId) {
        // uploadId 必须以 staging/{tenantId}/ 开头，防跨租户读取
        if (uploadId == null || !uploadId.startsWith("staging/" + TenantContext.requireTenantId() + "/")) {
            throw new BizException(400, "上传凭证非法");
        }
        try (var in = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(uploadId).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BizException(400, "暂存文件已失效，请重新上传");
        }
    }

    private List<Map<String, String>> parseColumns(String json) {
        try {
            return om.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            throw new BizException(500, "列定义损坏");
        }
    }

    private String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** MySQL 用反引号，其余（PG/SQL Server/Oracle）用双引号。 */
    private String quoteIdent(DataSourceEntity ds, String ident) {
        if ("mysql".equals(ds.getType())) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return quote(ident);
    }

    /** schema 限定名；schema 为空时落库默认 schema（裸表名）。 */
    private String qualified(DataSourceEntity ds, String schema, String ident) {
        return schema == null || schema.isBlank() ? quoteIdent(ds, ident)
                : quoteIdent(ds, schema) + "." + quoteIdent(ds, ident);
    }

    private String toColumnsJson(List<String> columns, List<String> types) {
        try {
            List<Map<String, String>> defs = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) defs.add(Map.of("name", columns.get(i), "type", types.get(i)));
            return om.writeValueAsString(defs);
        } catch (Exception e) {
            throw new BizException(500, "列定义序列化失败");
        }
    }
}
