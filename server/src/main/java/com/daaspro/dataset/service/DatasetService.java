package com.daaspro.dataset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.dataset.entity.DatasetEntity;
import com.daaspro.dataset.mapper.DatasetMapper;
import com.daaspro.datasource.entity.DataSourceEntity;
import com.daaspro.datasource.service.ConnectionManager;
import com.daaspro.datasource.service.DataSourceService;
import com.daaspro.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class DatasetService {

    private static final Set<String> ALLOWED_TYPES = Set.of("bigint", "numeric", "text", "timestamp", "date", "boolean");
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private final DatasetMapper mapper;
    private final DataSourceService dataSourceService;
    private final ConnectionManager connectionManager;
    private final TabularParser parser;
    private final MinioClient minio;
    private final String bucket;
    private final ObjectMapper om = new ObjectMapper();

    public DatasetService(DatasetMapper mapper, DataSourceService dataSourceService,
                          ConnectionManager connectionManager, TabularParser parser,
                          MinioClient minio, @Value("${daaspro.minio.bucket}") String bucket) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
        this.connectionManager = connectionManager;
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
                             String existingTable) {}

    /**
     * 确认导入。datasourceId 为空 → 内置数据源建新表；
     * datasourceId=内置源 且 existingTable 非空 → 向已有表追加（校验列名完全一致）。
     * 外部数据源不支持写入导入，MVP 只读。
     */
    public DatasetEntity confirm(ConfirmReq req) {
        if (req.columns() == null || req.columns().isEmpty() || req.types() == null
                || req.columns().size() != req.types().size()) {
            throw new BizException(400, "列定义不完整");
        }
        if (req.types().stream().anyMatch(t -> !ALLOWED_TYPES.contains(t))) {
            throw new BizException(400, "列类型非法");
        }
        if (req.name() == null || req.name().isBlank()) throw new BizException(400, "数据集名称不能为空");

        DataSourceEntity target;
        if (req.datasourceId == null) {
            target = builtinDatasource();
        } else {
            target = dataSourceService.getOwned(req.datasourceId());
            if (!Boolean.TRUE.equals(target.getBuiltin())) {
                throw new BizException(400, "外部数据源暂不支持上传导入（MVP 只读），请选择内置数据源");
            }
        }
        // 追加只对内置源有意义；未传 datasourceId 时也允许 append（默认内置源）
        boolean append = req.existingTable() != null && !req.existingTable().isBlank();

        Long dup = mapper.selectCount(new LambdaQueryWrapper<DatasetEntity>()
                .eq(DatasetEntity::getTenantId, TenantContext.requireTenantId())
                .eq(DatasetEntity::getName, req.name()));
        if (dup > 0) throw new BizException(400, "同名数据集已存在");

        byte[] csv = download(req.uploadId());
        String table;
        long copyCount;
        List<String> columns = req.columns();
        List<String> types = req.types();

        if (append) {
            table = req.existingTable();
            // 校验目标表存在且列名一致（列名集合与顺序都要求一致，导入语义最简单可靠）
            List<Map<String, String>> actual = tableColumns(target, table);
            if (actual.isEmpty()) throw new BizException(400, "目标表不存在: " + table);
            List<String> actualNames = actual.stream().map(c -> c.get("name")).toList();
            if (!actualNames.equals(columns)) {
                throw new BizException(400, "列与目标表不一致。目标表列: " + actualNames);
            }
            copyCount = copyInto(target, table, columns, csv, true);
        } else {
            table = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            copyCount = createTableAndCopy(target, table, columns, types, csv);
        }

        DatasetEntity ds = new DatasetEntity();
        ds.setTenantId(TenantContext.requireTenantId());
        ds.setName(req.name());
        ds.setTableName(table);
        ds.setDatasourceId(target.getId());
        List<Map<String, String>> actualCols = append ? tableColumns(target, table) : null;
        List<String> finalCols = append ? actualCols.stream().map(c -> c.get("name")).toList() : columns;
        List<String> finalTypes = append ? actualCols.stream().map(c -> c.get("type")).toList() : types;
        ds.setColumnsDef(toColumnsJson(finalCols, finalTypes));
        ds.setFileName(req.fileName());
        ds.setRowCount(copyCount);
        mapper.insert(ds);
        return ds;
    }

    /** 建表并 COPY 导入，返回导入行数。 */
    private long createTableAndCopy(DataSourceEntity ds, String table,
                                    List<String> columns, List<String> types, byte[] csv) {
        String colDefs = IntStream.range(0, columns.size())
                .mapToObj(i -> quote(columns.get(i)) + " " + types.get(i))
                .reduce((a, b) -> a + ", " + b).orElse("");
        String ddl = "CREATE TABLE " + quote(table) + " (" + colDefs + ")";
        try (Connection conn = openConnection(ds)) {
            try (var st = conn.createStatement()) {
                st.execute(ddl);
            }
            return copyInto(ds, table, columns, csv, true);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "导入失败: " + DataSourceService.rootMessage(e));
        }
    }

    private long copyInto(DataSourceEntity ds, String table, List<String> columns, byte[] csv, boolean header) {
        String copySql = "COPY " + quote(table) + " (" + columns.stream().map(this::quote)
                .reduce((a, b) -> a + "," + b).orElse("")
                + ") FROM STDIN WITH (FORMAT csv, HEADER " + header + ", NULL '')";
        try (Connection conn = openConnection(ds)) {
            CopyManager cm = conn.unwrap(PGConnection.class).getCopyAPI();
            return cm.copyIn(copySql, new ByteArrayInputStream(csv), 8192);
        } catch (Exception e) {
            throw new BizException(400, "导入失败: " + DataSourceService.rootMessage(e));
        }
    }

    private Connection openConnection(DataSourceEntity ds) {
        try {
            if (Boolean.TRUE.equals(ds.getBuiltin())) {
                return connectionManager.builtinPool(ds.getId(), ds.getSchemaName()).getConnection();
            }
            throw new BizException(400, "该数据源不支持导入");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "获取连接失败: " + DataSourceService.rootMessage(e));
        }
    }

    /** 读取目标表列结构（内置 PG 源）。 */
    private List<Map<String, String>> tableColumns(DataSourceEntity ds, String table) {
        String sql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection conn = openConnection(ds);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, ds.getSchemaName());
            st.setString(2, table);
            try (var rs = st.executeQuery()) {
                var rows = new ArrayList<Map<String, String>>();
                while (rs.next()) rows.add(Map.of("name", rs.getString(1), "type", rs.getString(2)));
                return rows;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "读取表结构失败: " + DataSourceService.rootMessage(e));
        }
    }

    public List<Map<String, Object>> list() {
        return mapper.selectList(new LambdaQueryWrapper<DatasetEntity>()
                .eq(DatasetEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(DatasetEntity::getId)).stream()
                .map(d -> Map.<String, Object>of("id", d.getId(), "name", d.getName(),
                        "tableName", d.getTableName(), "rowCount", d.getRowCount(),
                        "fileName", d.getFileName() == null ? "" : d.getFileName(),
                        "createdAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString()))
                .toList();
    }

    public record Preview(List<Map<String, String>> columns, List<List<String>> rows) {}

    public Preview preview(Long id) {
        DatasetEntity d = getOwned(id);
        List<Map<String, String>> cols = parseColumns(d.getColumnsDef());
        String sql = "SELECT * FROM " + quote(d.getTableName()) + " LIMIT 20";
        DataSourceEntity builtin = builtinDatasource();
        try (Connection conn = connectionManager.builtinPool(builtin.getId(), builtin.getSchemaName()).getConnection();
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
        DatasetEntity d = getOwned(id);
        DataSourceEntity builtin = builtinDatasource();
        try (Connection conn = connectionManager.builtinPool(builtin.getId(), builtin.getSchemaName()).getConnection();
             var st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quote(d.getTableName()));
        } catch (Exception e) {
            throw new BizException(400, "删除失败: " + DataSourceService.rootMessage(e));
        }
        mapper.deleteById(id);
    }

    private DataSourceEntity builtinDatasource() {
        var list = dataSourceService.list().stream()
                .filter(d -> Boolean.TRUE.equals(d.getBuiltin())).toList();
        if (list.isEmpty()) throw new BizException(400, "内置数据源缺失");
        return list.get(0);
    }

    private DatasetEntity getOwned(Long id) {
        DatasetEntity d = mapper.selectById(id);
        if (d == null || !d.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "数据集不存在");
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
