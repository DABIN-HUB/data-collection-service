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
    public ApiResult<CloudOutboxDetailResponse> detail(@PathVariable String messageId) {
        return service.detail(messageId).map(ApiResult::success)
                .orElseGet(() -> ApiResult.error(404, "云端发件箱消息不存在"));
    }

    @PostMapping("/outbox/{messageId}/replay")
    public ApiResult<Void> replay(@PathVariable String messageId) {
        return service.replay(messageId);
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
