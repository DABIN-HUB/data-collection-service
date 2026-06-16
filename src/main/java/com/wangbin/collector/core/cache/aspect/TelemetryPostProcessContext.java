package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

/**
 * Immutable telemetry post-processing payload shared by all pipeline stages.
 */
public record TelemetryPostProcessContext(String deviceId,
                                          DataPoint point,
                                          ProcessResult processResult,
                                          Object cacheValue,
                                          long eventTs) {
}
