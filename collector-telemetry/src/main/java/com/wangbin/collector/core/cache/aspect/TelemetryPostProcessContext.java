package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

/**
 * 所有后处理阶段共享的不可变遥测上下文。
 */
public record TelemetryPostProcessContext(String deviceId,
                                          DataPoint point,
                                          ProcessResult processResult,
                                          Object cacheValue,
                                          long eventTs,
                                          Long generation) {
}
