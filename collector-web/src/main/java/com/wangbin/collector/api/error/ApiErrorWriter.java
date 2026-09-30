package com.wangbin.collector.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.web.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/** 统一写出 HTTP 错误响应，保留现有 ApiResult JSON 骨架。 */
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int httpStatus,
                      String machineCode, String message, Object data) throws IOException {
        ApiResult<Object> result = ApiErrorResponseFactory.createBody(request, HttpStatus.valueOf(httpStatus),
                machineCode, message, data);
        Object requestId = result.getExtra("requestId");
        if (requestId != null) {
            response.setHeader("X-Request-Id", String.valueOf(requestId));
        }
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), result);
    }
}
