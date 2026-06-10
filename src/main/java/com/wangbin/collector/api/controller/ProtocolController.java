package com.wangbin.collector.api.controller;

import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.common.web.result.ResultCode;
import com.wangbin.collector.core.config.protocol.ProtocolFieldConfig;
import com.wangbin.collector.core.config.protocol.ProtocolSchema;
import com.wangbin.collector.core.config.protocol.ProtocolSchemaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Protocol metadata APIs used by the visual admin console.
 */
@RestController
@RequestMapping("/api/protocols")
@RequiredArgsConstructor
public class ProtocolController {

    private final ProtocolSchemaService protocolSchemaService;

    @GetMapping
    public ApiResult<List<ProtocolSchema>> listProtocols() {
        return ApiResult.success(protocolSchemaService.getAllSchemas());
    }

    @GetMapping("/{protocol}")
    public ApiResult<ProtocolSchema> getProtocol(@PathVariable String protocol) {
        return protocolSchemaService.getSchema(protocol)
                .map(ApiResult::success)
                .orElseGet(() -> ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(),
                        "不支持的协议: " + protocol));
    }

    @GetMapping("/{protocol}/fields")
    public ApiResult<List<ProtocolFieldConfig>> getConnectionFields(@PathVariable String protocol) {
        if (protocolSchemaService.getSchema(protocol).isEmpty()) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "不支持的协议: " + protocol);
        }
        return ApiResult.success(protocolSchemaService.getConnectionFields(protocol));
    }
}
