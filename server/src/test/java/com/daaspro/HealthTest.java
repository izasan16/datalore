package com.daaspro;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthReturnsVersion() {
        var body = rest.getForEntity("/api/health", String.class).getBody();
        assertThat(body).contains("\"status\":\"ok\"").contains("0.1.0");
    }
}
