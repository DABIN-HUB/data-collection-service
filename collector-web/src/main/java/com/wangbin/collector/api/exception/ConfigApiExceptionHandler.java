package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.controller.ConfigController;
import com.wangbin.collector.common.web.result.ApiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import com.wangbin.collector.api.filter.RequestCorrelationFilter;

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
    public ResponseEntity<ApiResult<Object>> handleConfigApiException(ConfigApiException exception,
                                                                        HttpServletRequest request) {
        String machineCode = exception.getHttpStatus().value() == 409
                ? "CONFIG_VERSION_CONFLICT" : "CONFIG_OPERATION_FAILED";
        ApiResult<Object> result = ApiResult.statusError(machineCode, exception.getMessage(), exception.getData());
        Object requestId = request.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID);
        if (requestId != null) result.setRequestId(String.valueOf(requestId));
        return ResponseEntity.status(exception.getHttpStatus()).body(result);
    }
}
