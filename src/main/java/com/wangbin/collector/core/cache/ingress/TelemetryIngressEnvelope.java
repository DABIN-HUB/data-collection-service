package com.wangbin.collector.core.cache.ingress;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;

/**
 * 可重放的单条遥测入口载荷。
 */
public final class TelemetryIngressEnvelope {

    private String deviceId;
    private DataPoint point;
    private ProcessResult processResult;
    private Object cacheValue;
    private ProcessResult cacheProcessResult;
    private boolean cacheValueProcessResult;
    private long eventTs;
    private Long generation;
    private String runtimeId;

    /**
     * Jackson 反序列化构造器。
     */
    public TelemetryIngressEnvelope() {
    }

    private TelemetryIngressEnvelope(String deviceId,
                                     DataPoint point,
                                     ProcessResult processResult,
                                     Object cacheValue,
                                     ProcessResult cacheProcessResult,
                                     boolean cacheValueProcessResult,
                                     long eventTs,
                                     Long generation,
                                     String runtimeId) {
        this.deviceId = deviceId;
        this.point = point;
        this.processResult = processResult;
        this.cacheValue = cacheValue;
        this.cacheProcessResult = cacheProcessResult;
        this.cacheValueProcessResult = cacheValueProcessResult;
        this.eventTs = eventTs;
        this.generation = generation;
        this.runtimeId = runtimeId;
    }

    static TelemetryIngressEnvelope from(TelemetryPostProcessContext context, String runtimeId) {
        Object cacheValue = context.cacheValue();
        boolean cacheIsProcessResult = cacheValue instanceof ProcessResult;
        return new TelemetryIngressEnvelope(
                context.deviceId(),
                context.point(),
                snapshot(context.processResult()),
                cacheIsProcessResult ? null : cacheValue,
                cacheIsProcessResult ? snapshot((ProcessResult) cacheValue) : null,
                cacheIsProcessResult,
                context.eventTs(),
                context.generation(),
                runtimeId);
    }

    TelemetryPostProcessContext toContext() {
        return new TelemetryPostProcessContext(
                deviceId,
                point,
                processResult,
                cacheValueProcessResult ? cacheProcessResult : cacheValue,
                eventTs,
                generation);
    }

    String deviceId() {
        return deviceId;
    }

    Long generation() {
        return generation;
    }

    String runtimeId() {
        return runtimeId;
    }

    private static ProcessResult snapshot(ProcessResult result) {
        return result == null ? null : result.snapshot();
    }
}
