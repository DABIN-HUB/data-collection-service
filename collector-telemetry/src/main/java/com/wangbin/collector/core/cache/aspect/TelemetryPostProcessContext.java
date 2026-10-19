package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.collector.scheduler.CollectionProcessingReceipt;

/**
 * 所有后处理阶段共享的不可变遥测上下文。
 */
public record TelemetryPostProcessContext(String deviceId,
                                          DataPoint point,
                                          ProcessResult processResult,
                                          Object cacheValue,
                                          long eventTs,
                                          Long generation,
                                          CollectionTaskGuard guard,
                                          CollectionProcessingReceipt receipt,
                                          boolean historicalOnly) {
    public TelemetryPostProcessContext(String deviceId, DataPoint point, ProcessResult processResult,
                                      Object cacheValue, long eventTs, Long generation) {
        this(deviceId, point, processResult, cacheValue, eventTs, generation, null, null, true);
    }

    public String source() {
        return processResult.getMetadata(ProcessResultMetadataKeys.SOURCE, "UNKNOWN");
    }

    public long sampleAt() {
        Object time = processResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME);
        return time instanceof Number number ? number.longValue() : 0L;
    }

    public boolean validValue() {
        return processResult.isSuccess() && processResult.getFinalValue() != null
                && processResult.getQuality() >= 0 && processResult.getQuality() <= 100
                && processResult.isQualityAcceptable() && sampleAt() > 0L && sampleAt() <= System.currentTimeMillis()
                && !"UNKNOWN".equals(source()) && !"CACHE_READ".equals(source());
    }

    public boolean live() {
        return !historicalOnly && guard != null && generation != null
                && guard.isCurrent(deviceId, generation)
                && (receipt == null || !receipt.isCancelled()) && !"CACHE_READ".equals(source());
    }
}
