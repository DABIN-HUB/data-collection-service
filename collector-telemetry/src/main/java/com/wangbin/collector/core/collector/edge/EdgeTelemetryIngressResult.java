package com.wangbin.collector.core.collector.edge;

import java.util.List;

/**
 * 边缘遥测批次处理结果。
 */
public record EdgeTelemetryIngressResult(String gatewayId,
                                         String configVersion,
                                         int acceptedCount,
                                         int duplicateCount,
                                         int rejectedCount,
                                         List<String> errors) {
}
