package com.wangbin.collector.api.error;

import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import com.wangbin.collector.common.web.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 统一生成已治理接口的错误响应体及 HTTP 状态。 */
public final class ApiErrorResponseFactory {

    private ApiErrorResponseFactory() {
    }

    public static <T> ApiResult<T> createBody(HttpServletRequest request, HttpStatus status,
                                               String machineCode, String message, T data) {
        ApiResult<T> result = ApiResult.statusError(machineCode, message, data);
        result.setCode(status.value());
        Object requestId = request.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID);
        if (requestId != null) {
            result.setRequestId(String.valueOf(requestId));
        }
        return result;
    }

    public static <T> ResponseEntity<ApiResult<T>> response(HttpServletRequest request, HttpStatus status,
                                                              String machineCode, String message, T data) {
        return ResponseEntity.status(status).body(createBody(request, status, machineCode, message, data));
    }
}
