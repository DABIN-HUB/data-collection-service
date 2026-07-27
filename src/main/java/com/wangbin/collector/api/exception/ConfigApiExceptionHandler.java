package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.controller.ConfigController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置治理接口统一异常响应处理器。
 */
@RestControllerAdvice(assignableTypes = ConfigController.class)
public class ConfigApiExceptionHandler {

    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<Map<String, Object>> handleConfigApiException(ConfigApiException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("message", exception.getMessage());
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("data", exception.getData());
        return ResponseEntity.status(exception.getHttpStatus()).body(payload);
    }
}
