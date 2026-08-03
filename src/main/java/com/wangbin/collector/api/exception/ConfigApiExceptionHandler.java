package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.controller.ConfigController;
import com.wangbin.collector.api.controller.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 配置治理接口统一异常响应处理器。
 */
@RestControllerAdvice(assignableTypes = ConfigController.class)
public class ConfigApiExceptionHandler {

    /**
     * 处理配置接口业务异常。
     *
     * @param exception 配置接口异常
     * @return 统一异常响应
     */
    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleConfigApiException(ConfigApiException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(ApiResponse.error(exception.getMessage(), exception.getData()));
    }
}