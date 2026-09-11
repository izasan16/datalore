package com.daaspro.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 401/403 必须带 JSON body：部分 HTTP 客户端（如 JDK HttpURLConnection）
     * 对无 body 的 401 会走认证重试逻辑抛 HttpRetryException。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<String> biz(BizException e) {
        HttpStatus status = e.getCode() == 401 ? HttpStatus.UNAUTHORIZED
                : e.getCode() == 403 ? HttpStatus.FORBIDDEN
                : HttpStatus.BAD_REQUEST;
        String json = "{\"code\":" + e.getCode()
                + ",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("参数错误");
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", msg));
    }
}
