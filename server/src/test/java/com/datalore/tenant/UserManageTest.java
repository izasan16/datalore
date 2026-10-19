package com.datalore.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserManageTest {

    @Autowired
    TestRestTemplate bootRest;

    RestTemplate rest;

    RestTemplate rest() {
        if (rest == null) {
            rest = new RestTemplate(
                    new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
            rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
                @Override
                public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                }
            });
        }
        return rest;
    }

    private String url(String path) {
        return bootRest.getRootUri() + path;
    }

    private String registerAndLogin() {
        String adminUser = "admu" + System.currentTimeMillis();
        rest().postForEntity(url("/api/tenant/register"), Map.of("name", "用户管理租户" + adminUser,
                "adminUsername", adminUser, "adminPassword", "password123"), Map.class);
        var login = rest().postForEntity(url("/api/auth/login"),
                Map.of("username", adminUser, "password", "password123"), Map.class);
        return (String) login.getBody().get("token");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void adminCreatesAndDisablesMember() {
        String token = registerAndLogin();
        String memberUser = "zhang" + System.currentTimeMillis();
        var create = rest().exchange(url("/api/users"), HttpMethod.POST,
                new HttpEntity<>(Map.of("username", memberUser, "password", "password123",
                        "displayName", "张三", "role", "member"), auth(token)), Map.class);
        assertThat(create.getStatusCode().is2xxSuccessful()).isTrue();
        Number id = (Number) create.getBody().get("id");

        var list = rest().exchange(url("/api/users"), HttpMethod.GET,
                new HttpEntity<>(auth(token)), java.util.List.class);
        assertThat((java.util.List<Object>) list.getBody()).hasSize(2);

        var disable = rest().exchange(url("/api/users/" + id + "/disable"), HttpMethod.POST,
                new HttpEntity<>(auth(token)), Map.class);
        assertThat(disable.getStatusCode().is2xxSuccessful()).isTrue();

        // 被停用用户无法登录
        var login = rest().postForEntity(url("/api/auth/login"),
                Map.of("username", memberUser, "password", "password123"), Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void memberCannotCreateUser() {
        String adminToken = registerAndLogin();
        String memberUser = "zhang" + System.currentTimeMillis();
        rest().exchange(url("/api/users"), HttpMethod.POST,
                new HttpEntity<>(Map.of("username", memberUser, "password", "password123", "role", "member"),
                        auth(adminToken)), Map.class);
        var memberLogin = rest().postForEntity(url("/api/auth/login"),
                Map.of("username", memberUser, "password", "password123"), Map.class);
        String memberToken = (String) memberLogin.getBody().get("token");
        var denied = rest().exchange(url("/api/users"), HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "li", "password", "password123", "role", "member"),
                        auth(memberToken)), Map.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
    }
}
