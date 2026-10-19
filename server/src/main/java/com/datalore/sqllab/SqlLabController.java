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

    public record RunReq(String datasourceId, @NotBlank String sql) {}

    @PostMapping("/run")
    public SqlGatewayService.QueryResult run(@RequestBody RunReq req) {
        return gateway.run(req.datasourceId(), req.sql());
    }
}
