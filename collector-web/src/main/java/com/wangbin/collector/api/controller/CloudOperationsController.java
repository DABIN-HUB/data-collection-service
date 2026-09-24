package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.CloudOperationsApplicationService;
import com.wangbin.collector.api.controller.dto.CloudFlushResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxDetailResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxItemResponse;
import com.wangbin.collector.api.controller.dto.CloudTestResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/api/cloud")
@RequiredArgsConstructor
public class CloudOperationsController {
    private final CloudOperationsApplicationService service;

    @GetMapping("/outbox")
    public ApiResult<List<CloudOutboxItemResponse>> list(@RequestParam(required = false) CloudOutboxStatus status,
                                                          @RequestParam(required = false) String deviceId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.success(service.list(status, deviceId, limit));
    }

    @GetMapping("/outbox/{messageId}")
    public ResponseEntity<ApiResult<CloudOutboxDetailResponse>> detail(@PathVariable String messageId) {
        return service.detail(messageId).map(value -> ResponseEntity.ok(ApiResult.success(value)))
                .orElseGet(() -> {
                    ApiResult<CloudOutboxDetailResponse> result = ApiResult.statusError(
                            "RESOURCE_NOT_FOUND", "云端发件箱消息不存在", null);
                    result.setCode(HttpStatus.NOT_FOUND.value());
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
                });
    }

    @PostMapping("/outbox/{messageId}/replay")
    public ResponseEntity<ApiResult<Void>> replay(@PathVariable String messageId) {
        var detail = service.detail(messageId);
        if (detail.isEmpty()) {
            ApiResult<Void> result = ApiResult.statusError("RESOURCE_NOT_FOUND", "云端发件箱消息不存在", null);
            result.setCode(HttpStatus.NOT_FOUND.value());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        if (detail.get().getSummary().getStatus() != CloudOutboxStatus.ISOLATED || !service.replay(messageId).isSuccess()) {
            ApiResult<Void> result = ApiResult.statusError("CLOUD_OUTBOX_STATE_CONFLICT",
                    "消息状态已发生变化，请刷新列表后重试", null);
            result.setCode(HttpStatus.CONFLICT.value());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.ok(ApiResult.success("云端发件箱消息已重新进入发送队列", null));
    }

    @PostMapping("/flush")
    public ApiResult<CloudFlushResponse> flush() {
        return ApiResult.success(service.flush());
    }

    @PostMapping("/test")
    public ApiResult<CloudTestResponse> test() {
        return ApiResult.success(service.test());
    }
}
