package com.wangbin.collector.core.cache.ingress;

import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;

import java.util.List;

/**
 * 遥测后处理入口过载缓冲。
 */
public interface TelemetryIngressBuffer {

    /**
     * 将尚未进入后处理流水线的遥测上下文转入可靠缓冲。
     */
    TelemetryIngressBufferResult defer(List<TelemetryPostProcessContext> contexts, RuntimeException cause);

    /**
     * 返回入口过载缓冲指标快照。
     */
    TelemetryIngressBufferMetrics metrics();

    /**
     * 测试和禁用场景使用的空实现，所有输入都会被明确计为丢弃。
     */
    static TelemetryIngressBuffer noop() {
        return new TelemetryIngressBuffer() {
            @Override
            public TelemetryIngressBufferResult defer(List<TelemetryPostProcessContext> contexts, RuntimeException cause) {
                int items = contexts == null ? 0 : contexts.size();
                return new TelemetryIngressBufferResult(items, 0, 0, items);
            }

            @Override
            public TelemetryIngressBufferMetrics metrics() {
                return TelemetryIngressBufferMetrics.empty();
            }
        };
    }
}
