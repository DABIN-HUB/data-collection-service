package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.EdgeTelemetryBatchRequest;
import com.wangbin.collector.api.controller.dto.EdgeTelemetryItem;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.edge.EdgeTelemetryBatch;
import com.wangbin.collector.core.collector.edge.EdgeTelemetryIngressResult;
import com.wangbin.collector.core.collector.edge.EdgeTelemetryIngressService;
import com.wangbin.collector.core.collector.edge.EdgeTelemetrySample;
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
    public ApiResult<EdgeTelemetryIngressResult> ingest(
            @Valid @RequestBody EdgeTelemetryBatchRequest request) {
        return ApiResult.statusSuccess("边缘遥测处理完成", ingressService.ingest(toBatch(request)));
    }

    private EdgeTelemetryBatch toBatch(EdgeTelemetryBatchRequest request) {
        return new EdgeTelemetryBatch(
                request.gatewayId(),
                request.protocol(),
                request.configVersion(),
                request.items().stream().map(this::toSample).toList());
    }

    private EdgeTelemetrySample toSample(EdgeTelemetryItem item) {
        return new EdgeTelemetrySample(
                item.deviceId(),
                item.pointRef(),
                item.value(),
                item.quality(),
                item.timestamp(),
                item.sequence());
    }
}
