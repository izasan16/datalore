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
class AuthFlowTest {

    @Autowired
    TestRestTemplate bootRest;

    RestTemplate rest;

    RestTemplate rest() {
        if (rest == null) {
            // JDK HttpURLConnection 对 401 POST 有认证重试 quirk（HttpRetryException），
            // 换 Apache HttpClient 实现；关闭错误抛出以便断言 401/400 状态码
            rest = new RestTemplate(
                    new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
            rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
                @Override
                public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                    // 不抛异常，让测试自行断言状态码
                }
            });
        }
        return rest;
    }

    private String url(String path) {
        return bootRest.getRootUri() + path;
    }

    @Test
    void registerLoginMeFlow() {
        String name = "流程租户" + System.currentTimeMillis();
        String adminUser = "admin_" + System.currentTimeMillis();
        var reg = rest().postForEntity(url("/api/tenant/register"),
                Map.of("name", name, "adminUsername", adminUser, "adminPassword", "password123"), Map.class);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();

        var loginReq = Map.of("username", adminUser, "password", "password123");
        ResponseEntity<Map> login = rest().postForEntity(url("/api/auth/login"), loginReq, Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> me = rest().exchange(url("/api/auth/me"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(me.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(me.getBody().get("username")).isEqualTo(adminUser);

        ResponseEntity<Map> noAuth = rest().getForEntity(url("/api/auth/me"), Map.class);
        assertThat(noAuth.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrongPasswordRejected() {
        rest().postForEntity(url("/api/tenant/register"), Map.of("name", "流程租户B" + System.currentTimeMillis(),
                "adminUsername", "admin_" + System.nanoTime(), "adminPassword", "password123"), Map.class);
        var bad = rest().postForEntity(url("/api/auth/login"),
                Map.of("username", "nobody" + System.currentTimeMillis(), "password", "wrong-pass"), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
    }
}
