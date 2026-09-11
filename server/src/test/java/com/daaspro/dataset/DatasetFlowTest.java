package com.daaspro.dataset;

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
class DatasetFlowTest {

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

    private String token() {
        String adminUser = "dsflow" + System.currentTimeMillis();
        rest().postForEntity(url("/api/tenant/register"),
                Map.of("name", "数据集租户" + adminUser, "adminUsername", adminUser,
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
        String csv = "名称,数量,价格,日期\n甲,10,1.5,2026-01-01\n乙,20,2.25,2026-01-02\n,,,\n丙,30,3.5,2026-01-03\n";
        ByteArrayResource res = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "demo.csv"; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", res);
        var up = rest().postForEntity(url("/api/datasets/upload"),
                new HttpEntity<>(body, authMultipart(token)), Map.class);
        assertThat(up.getStatusCode().is2xxSuccessful()).isTrue();
        // ",,," 是含 3 个空字段的行（非空行），保留为 1 条全空记录
        assertThat(((Number) up.getBody().get("rowCount")).longValue()).isEqualTo(4L);
        assertThat((java.util.List<Object>) up.getBody().get("types"))
                .containsExactly("text", "bigint", "numeric", "date");

        var confirm = rest().postForEntity(url("/api/datasets/confirm"),
                new HttpEntity<>(Map.of(
                        "uploadId", up.getBody().get("uploadId"),
                        "name", "演示数据集" + System.currentTimeMillis(),
                        "fileName", "demo.csv",
                        "columns", up.getBody().get("columns"),
                        "types", up.getBody().get("types")), jsonHeaders(token)), Map.class);
        assertThat(confirm.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Number) confirm.getBody().get("rowCount")).longValue()).isEqualTo(4L);

        var list = rest().exchange(url("/api/datasets"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), java.util.List.class);
        assertThat((java.util.List<Object>) list.getBody()).hasSize(1);

        Number id = (Number) confirm.getBody().get("id");
        var preview = rest().exchange(url("/api/datasets/" + id + "/preview"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), Map.class);
        assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat((java.util.List<Object>) preview.getBody().get("rows")).hasSize(4);

        var del = rest().exchange(url("/api/datasets/" + id), HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(token)), Map.class);
        assertThat(del.getStatusCode().is2xxSuccessful()).isTrue();
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
        var up = rest().postForEntity(url("/api/datasets/upload"),
                new HttpEntity<>(body, authMultipart(t1)), Map.class);
        String uploadId = (String) up.getBody().get("uploadId");

        String t2 = token();
        var confirm = rest().postForEntity(url("/api/datasets/confirm"),
                new HttpEntity<>(Map.of(
                        "uploadId", uploadId,
                        "name", "越权数据集" + System.currentTimeMillis(),
                        "columns", up.getBody().get("columns"),
                        "types", up.getBody().get("types")), jsonHeaders(t2)), Map.class);
        assertThat(confirm.getStatusCode().value()).isEqualTo(400);
    }
}
