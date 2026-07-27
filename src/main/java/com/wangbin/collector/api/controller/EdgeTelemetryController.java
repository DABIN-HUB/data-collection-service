package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.ApiResponse;
import com.wangbin.collector.api.controller.dto.EdgeTelemetryBatchRequest;
import com.wangbin.collector.core.collector.edge.EdgeTelemetryIngressResult;
import com.wangbin.collector.core.collector.edge.EdgeTelemetryIngressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PROFINET、EtherCAT 等独立边缘进程的遥测接入接口。
 */
@RestController
@RequestMapping("/api/edge")
@RequiredArgsConstructor
public class EdgeTelemetryController {

    private final EdgeTelemetryIngressService ingressService;

    @PostMapping("/telemetry")
    public ApiResponse<EdgeTelemetryIngressResult> ingest(
            @Valid @RequestBody EdgeTelemetryBatchRequest request) {
        return ApiResponse.success("边缘遥测处理完成", ingressService.ingest(request));
    }
}
