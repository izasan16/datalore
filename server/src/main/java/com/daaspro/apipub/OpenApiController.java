package com.daaspro.apipub;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放接口执行端。免平台登录，凭 X-Api-Key / X-Api-Secret 头调用。
 * TABLE 模式：GET /api/open/{path}?col=val&page=1&size=100&order_by=col&order=asc
 * SQL 模式：GET /api/open/{path}?param=value（不分页，行数受 max_rows 截断）
 */
@RestController
@RequestMapping("/api/open")
public class OpenApiController {

    private final OpenApiService service;

    public OpenApiController(OpenApiService service) {
        this.service = service;
    }

    @GetMapping("/{path}")
    public ResponseEntity<Map<String, Object>> invoke(
            @PathVariable String path,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Api-Secret", required = false) String apiSecret,
            HttpServletRequest request) {
        var api = service.authenticate(apiKey, apiSecret);
        var resolved = service.resolve(path, api);

        Map<String, String> params = new LinkedHashMap<>();
        for (var e : request.getParameterMap().entrySet()) {
            String k = e.getKey();
            if (k.equals("page") || k.equals("size") || k.equals("order_by") || k.equals("order")) continue;
            params.put(k, e.getValue().length > 0 ? e.getValue()[0] : "");
        }
        var result = service.query(resolved, params,
                request.getParameter("page"), request.getParameter("size"),
                request.getParameter("order_by"), request.getParameter("order"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", result.columns());
        body.put("rows", result.rows());
        body.put("total", result.total());
        body.put("page", result.page());
        body.put("size", result.size());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(OpenApiService.OpenApiException.class)
    public ResponseEntity<Map<String, Object>> onError(OpenApiService.OpenApiException e) {
        HttpStatus status = switch (e.status) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 404 -> HttpStatus.NOT_FOUND;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("message", e.bodyMessage));
    }
}
