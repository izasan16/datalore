package com.daaspro.imported.service;

import com.daaspro.common.exception.BizException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 表格文件解析：列名清洗、类型推断（采样前 1000 行）、规范化 CSV 输出（RFC4180, UTF-8）。 */
@Component
public class TabularParser {

    public static final int MAX_PREVIEW = 20;
    private static final int TYPE_SAMPLE_ROWS = 1000;
    private static final Pattern INT = Pattern.compile("^[+-]?\\d{1,18}$");
    private static final Pattern DECIMAL = Pattern.compile("^[+-]?\\d+\\.\\d+$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");

    public record Parsed(List<String> columns, List<String> types,
                         List<List<String>> previewRows, long rowCount, byte[] normalizedCsv) {}

    public Parsed parse(String fileName, InputStream in) {
        List<List<String>> rows;
        try {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                rows = readExcel(in);
            } else if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
                rows = readCsv(in);
            } else {
                throw new BizException(400, "仅支持 CSV / XLSX 文件");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "文件解析失败: " + e.getMessage());
        }
        if (rows.isEmpty()) throw new BizException(400, "文件没有数据行");

        List<String> header = rows.get(0);
        List<String> columns = sanitizeColumns(header);
        List<List<String>> data = new ArrayList<>(rows.subList(1, rows.size()));
        if (data.isEmpty()) throw new BizException(400, "文件没有数据行");

        List<String> types = inferTypes(columns, data);
        byte[] csv = toCsv(columns, data);
        List<List<String>> preview = new ArrayList<>(data.subList(0, Math.min(MAX_PREVIEW, data.size())));
        return new Parsed(columns, types, preview, data.size(), csv);
    }

    private List<List<String>> readCsv(InputStream in) throws IOException {
        Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true).setTrim(true).build()
                .parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        List<List<String>> rows = new ArrayList<>();
        for (CSVRecord r : records) {
            List<String> row = new ArrayList<>(r.size());
            r.forEach(row::add);
            rows.add(row);
        }
        return rows;
    }

    private List<List<String>> readExcel(InputStream in) throws IOException {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = row.getLastCellNum();
                for (int i = 0; i < last; i++) {
                    cells.add(fmt.formatCellValue(row.getCell(i)));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    private List<String> sanitizeColumns(List<String> raw) {
        Map<String, Integer> used = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String name = raw.get(i) == null ? "" : raw.get(i).trim();
            name = name.replaceAll("[\"'\\\r\n]", "_");
            if (name.isEmpty()) name = "col_" + (i + 1);
            int n = used.getOrDefault(name, 0);
            used.put(name, n + 1);
            if (n > 0) name = name + "_" + (n + 1);
            out.add(name);
        }
        return out;
    }

    private List<String> inferTypes(List<String> columns, List<List<String>> data) {
        int cols = columns.size();
        boolean[] isInt = new boolean[cols];
        boolean[] isDec = new boolean[cols];
        boolean[] isDate = new boolean[cols];
        boolean[] isTs = new boolean[cols];
        boolean[] isBool = new boolean[cols];
        int[] samples = new int[cols];
        java.util.Arrays.fill(isInt, true);
        java.util.Arrays.fill(isDec, true);
        java.util.Arrays.fill(isDate, true);
        java.util.Arrays.fill(isTs, true);
        java.util.Arrays.fill(isBool, true);

        int checked = 0;
        for (List<String> row : data) {
            if (checked++ >= TYPE_SAMPLE_ROWS) break;
            for (int c = 0; c < cols; c++) {
                if (samples[c] >= TYPE_SAMPLE_ROWS) continue;
                String v = c < row.size() ? row.get(c) : "";
                if (v == null || v.isBlank()) continue;
                samples[c]++;
                isInt[c] &= INT.matcher(v).matches();
                isDec[c] &= INT.matcher(v).matches() || DECIMAL.matcher(v).matches();
                isDate[c] &= DATE.matcher(v).matches();
                isTs[c] &= TS.matcher(v).matches();
                isBool[c] &= v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false");
            }
        }

        List<String> types = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            if (samples[c] == 0) { types.add("text"); continue; }
            if (isInt[c]) types.add("bigint");
            else if (isDec[c]) types.add("numeric");
            else if (isTs[c]) types.add("timestamp");
            else if (isDate[c]) types.add("date");
            else if (isBool[c]) types.add("boolean");
            else types.add("text");
        }
        return types;
    }

    private byte[] toCsv(List<String> columns, List<List<String>> data) {
        try (StringWriter sw = new StringWriter();
             CSVPrinter printer = new CSVPrinter(sw, CSVFormat.RFC4180)) {
            printer.printRecord(columns);
            for (List<String> row : data) {
                List<String> padded = new ArrayList<>(columns.size());
                for (int i = 0; i < columns.size(); i++) {
                    padded.add(i < row.size() ? row.get(i) : "");
                }
                printer.printRecord(padded);
            }
            printer.flush();
            return sw.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(500, "CSV 序列化失败");
        }
    }
}
