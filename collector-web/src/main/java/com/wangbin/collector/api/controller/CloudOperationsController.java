package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.CloudOperationsApplicationService;
import com.wangbin.collector.api.application.CloudReportResultApplicationService;
import com.wangbin.collector.api.controller.dto.CloudFlushResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxDetailResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxItemResponse;
import com.wangbin.collector.api.controller.dto.CloudTestResponse;
import com.wangbin.collector.api.error.ApiErrorResponseFactory;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import com.wangbin.collector.core.report.outbox.CloudReportResultPage;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/api/cloud")
@RequiredArgsConstructor
public class CloudOperationsController {
    private final CloudOperationsApplicationService service;
    private final CloudReportResultApplicationService resultService;

    /**
     * 查询独立上报结果历史；计数只统计当前时间范围内已提交的真实业务 ACK。
     */
    @GetMapping("/results")
    public ResponseEntity<ApiResult<CloudReportResultPage>> results(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Long startTs,
            @RequestParam(required = false) Long endTs,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResult.success(resultService.query(deviceId, startTs, endTs, page, limit)));
        } catch (IllegalArgumentException exception) {
            return ApiErrorResponseFactory.response(request, HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_RANGE", exception.getMessage(), null);
        }
    }

    @GetMapping("/outbox")
    public ApiResult<List<CloudOutboxItemResponse>> list(@RequestParam(required = false) CloudOutboxStatus status,
                                                          @RequestParam(required = false) String deviceId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.success(service.list(status, deviceId, limit));
    }

    @GetMapping("/outbox/{messageId}")
    public ResponseEntity<ApiResult<CloudOutboxDetailResponse>> detail(@PathVariable String messageId,
                                                                       HttpServletRequest request) {
        return service.detail(messageId).map(value -> ResponseEntity.ok(ApiResult.success(value)))
                .orElseGet(() -> ApiErrorResponseFactory.response(request, HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", "云端发件箱消息不存在", null));
    }

    @PostMapping("/outbox/{messageId}/replay")
    public ResponseEntity<ApiResult<Void>> replay(@PathVariable String messageId, HttpServletRequest request) {
        var detail = service.detail(messageId);
        if (detail.isEmpty()) {
            return ApiErrorResponseFactory.response(request, HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND", "云端发件箱消息不存在", null);
        }
        if (detail.get().getSummary().getStatus() != CloudOutboxStatus.ISOLATED || !service.replay(messageId).isSuccess()) {
            return ApiErrorResponseFactory.response(request, HttpStatus.CONFLICT,
                    "CLOUD_OUTBOX_STATE_CONFLICT", "消息状态已发生变化，请刷新列表后重试", null);
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
