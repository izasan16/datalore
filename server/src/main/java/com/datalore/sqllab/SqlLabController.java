package com.datalore.sqllab;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql")
@Validated
public class SqlLabController {

    private final SqlGatewayService gateway;

    public SqlLabController(SqlGatewayService gateway) {
        this.gateway = gateway;
    }

    public record RunReq(String datasourceId, @NotBlank String sql, String runToken) {}

    @PostMapping("/run")
    public SqlGatewayService.QueryResult run(@RequestBody RunReq req) {
        return gateway.run(req.datasourceId(), req.sql(), req.runToken());
    }

    @PostMapping("/cancel")
    public java.util.Map<String, Object> cancel(@RequestBody(required = false) java.util.Map<String, String> body) {
        String runToken = body == null ? null : body.get("runToken");
        gateway.cancel(runToken);
        return java.util.Map.of("ok", true);
    }
}
