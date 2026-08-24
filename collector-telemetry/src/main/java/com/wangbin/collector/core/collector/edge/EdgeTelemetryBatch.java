package com.wangbin.collector.core.collector.edge;

import java.util.List;

/**
 * 边缘遥测批次输入模型，由 API 边界完成 HTTP DTO 到核心模型的转换。
 */
public record EdgeTelemetryBatch(String gatewayId,
                                 EdgeProtocolType protocol,
                                 String configVersion,
                                 List<EdgeTelemetrySample> items) {

    public EdgeTelemetryBatch {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
