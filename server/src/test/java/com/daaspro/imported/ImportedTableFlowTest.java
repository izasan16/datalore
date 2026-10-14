package com.daaspro.imported;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportedTableFlowTest {

    @Autowired TestRestTemplate bootRest;

    RestTemplate rest;

    RestTemplate rest() {
        if (rest == null) {
            rest = new RestTemplate(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
            rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
                @Override
                public void handleError(org.springframework.http.client.ClientHttpResponse response) {}
            });
        }
        return rest;
    }

    private String url(String p) { return bootRest.getRootUri() + p; }

    /** 新规则：导入必须显式指定目标数据源。注册一个指向平台 PG 的外部数据源。 */
    private long extDatasource(String token, String tag) {
        var created = rest().postForEntity(url("/api/datasources"),
                new HttpEntity<>(Map.of("name", "导入PG-" + tag, "type", "postgresql",
                        "host", "localhost", "port", 15432, "databaseName", "daaspro",
                        "schemaName", "public", "username", "daaspro", "password", "daaspro_dev_2026"),
                        jsonHeaders(token)), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        return ((Number) created.getBody().get("id")).longValue();
    }

    private String token() {
        String adminUser = "dsflow" + System.currentTimeMillis();
        rest().postForEntity(url("/api/tenant/register"),
                Map.of("name", "导入租户" + adminUser, "adminUsername", adminUser,
                        "adminPassword", "password123"), Map.class);
        var login = rest().postForEntity(url("/api/auth/login"),
                Map.of("username", adminUser, "password", "password123"), Map.class);
        return (String) login.getBody().get("token");
    }

    private HttpHeaders authMultipart(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return h;
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void uploadConfirmPreviewDelete() {
        String token = token();
        long dsId = extDatasource(token, "up" + System.currentTimeMillis());
        String csv = "名称,数量,价格,日期\n甲,10,1.5,2026-01-01\n乙,20,2.25,2026-01-02\n,,,\n丙,30,3.5,2026-01-03\n";
        ByteArrayResource res = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "demo.csv"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", res);
        var up = rest().postForEntity(url("/api/imported-tables/upload"),
                new HttpEntity<>(body, authMultipart(token)), Map.class);
        assertThat(up.getStatusCode().is2xxSuccessful()).isTrue();
        // ",,," 是含 3 个空字段的行（非空行），保留为 1 条全空记录
        assertThat(((Number) up.getBody().get("rowCount")).longValue()).isEqualTo(4L);
        assertThat((java.util.List<Object>) up.getBody().get("types"))
                .containsExactly("text", "bigint", "numeric", "date");

        var confirm = rest().postForEntity(url("/api/imported-tables/confirm"),
                new HttpEntity<>(Map.of(
                        "uploadId", up.getBody().get("uploadId"),
                        "name", "demo_import_" + System.currentTimeMillis(),
                        "fileName", "demo.csv",
                        "columns", up.getBody().get("columns"),
                        "types", up.getBody().get("types"),
                        "datasourceId", dsId), jsonHeaders(token)), Map.class);
        assertThat(confirm.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Number) confirm.getBody().get("rowCount")).longValue()).isEqualTo(4L);

        var list = rest().exchange(url("/api/imported-tables"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), java.util.List.class);
        assertThat((java.util.List<Object>) list.getBody()).hasSize(1);

        Number id = (Number) confirm.getBody().get("id");
        var preview = rest().exchange(url("/api/imported-tables/" + id + "/preview"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), Map.class);
        assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat((java.util.List<Object>) preview.getBody().get("rows")).hasSize(4);

        var del = rest().exchange(url("/api/imported-tables/" + id), HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(token)), Map.class);
        assertThat(del.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void appendToExistingTable() {
        String token = token();
        long dsId = extDatasource(token, "ap" + System.currentTimeMillis());
        // 第一次上传导入建表
        String csv1 = "名称,数量\n甲,10\n乙,20\n";
        Map<String, Object> first = doImport(token, csv1, "append_test_" + System.currentTimeMillis(), null, dsId);
        String table = (String) first.get("tableName");

        // 第二次同结构文件追加：应合并进第一个数据集（不新建记录，避免共享物理表被连坐删除）
        String csv2 = "名称,数量\n丙,30\n";
        Map<String, Object> second = doImport(token, csv2, "ignored" + System.currentTimeMillis(), table, dsId);
        assertThat(((Number) second.get("id")).longValue()).isEqualTo(((Number) first.get("id")).longValue());

        // 预览第一批：表里现在应该有 3 行（preview LIMIT 20 覆盖全部）
        var preview = rest().exchange(url("/api/imported-tables/" + ((Number) first.get("id")).longValue() + "/preview"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(token)), Map.class);
        assertThat((java.util.List<Object>) preview.getBody().get("rows")).hasSize(3);
    }

    private Map<String, Object> doImport(String token, String csv, String name, String existingTable, long datasourceId) {
        ByteArrayResource res = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "part.csv"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", res);
        var up = rest().postForEntity(url("/api/imported-tables/upload"),
                new HttpEntity<>(body, authMultipart(token)), Map.class);
        var payload = new java.util.HashMap<String, Object>();
        payload.put("uploadId", up.getBody().get("uploadId"));
        payload.put("name", name);
        payload.put("fileName", "part.csv");
        payload.put("columns", up.getBody().get("columns"));
        payload.put("types", up.getBody().get("types"));
        payload.put("datasourceId", datasourceId);
        if (existingTable != null) payload.put("existingTable", existingTable);
        var confirm = rest().postForEntity(url("/api/imported-tables/confirm"),
                new HttpEntity<>(payload, jsonHeaders(token)), Map.class);
        assertThat(confirm.getStatusCode().is2xxSuccessful()).isTrue();
        return confirm.getBody();
    }

    private String getTableName(String token, Number id) {
        var list = rest().exchange(url("/api/imported-tables"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), java.util.List.class);
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Map<String, Object>>) list.getBody();
        return rows.stream().filter(r -> ((Number) r.get("id")).longValue() == id.longValue())
                .findFirst().map(r -> (String) r.get("tableName")).orElseThrow();
    }

    @Test
    void crossTenantUploadIdRejected() {
        String t1 = token();
        String csv = "a\n1\n";
        ByteArrayResource res = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "one.csv"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", res);
        var up = rest().postForEntity(url("/api/imported-tables/upload"),
                new HttpEntity<>(body, authMultipart(t1)), Map.class);
        String uploadId = (String) up.getBody().get("uploadId");

        String t2 = token();
        long dsId2 = extDatasource(t2, "x2" + System.currentTimeMillis());
        var confirm = rest().postForEntity(url("/api/imported-tables/confirm"),
                new HttpEntity<>(Map.of(
                        "uploadId", uploadId,
                        "name", "cross_tenant_" + System.currentTimeMillis(),
                        "columns", up.getBody().get("columns"),
                        "types", up.getBody().get("types"),
                        "datasourceId", dsId2), jsonHeaders(t2)), Map.class);
        assertThat(confirm.getStatusCode().value()).isEqualTo(400);
    }
}
