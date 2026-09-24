package com.wangbin.collector.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import com.wangbin.collector.common.web.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 统一写出 HTTP 错误响应，保留现有 ApiResult JSON 骨架。 */
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int httpStatus,
                      String machineCode, String message, Object data) throws IOException {
        ApiResult<Object> result = ApiResult.statusError(machineCode, message, data);
        result.setCode(httpStatus);
        Object requestId = request.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID);
        if (requestId == null) {
            requestId = request.getHeader("X-Request-Id");
        }
        if (requestId != null) {
            result.setRequestId(String.valueOf(requestId));
            response.setHeader("X-Request-Id", String.valueOf(requestId));
        }
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), result);
    }
}
