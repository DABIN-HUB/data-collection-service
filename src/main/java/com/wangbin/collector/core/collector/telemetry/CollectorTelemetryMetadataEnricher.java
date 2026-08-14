package com.wangbin.collector.core.collector.telemetry;

import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;

/**
 * 采集器通用遥测元数据补充器。
 *
 * <p>仅补充采集结果的通用元数据，不持有采集器生命周期、连接、统计或订阅运行态。</p>
 */
public final class CollectorTelemetryMetadataEnricher {

    public void enrich(ProcessResult result,
                       Object rawValue,
                       Object processedValue,
                       long collectTime,
                       String source) {
        if (result == null) {
            return;
        }
        putIfAbsent(result, ProcessResultMetadataKeys.RAW_VALUE, rawValue);
        putIfAbsent(result, ProcessResultMetadataKeys.PROCESSED_VALUE, processedValue);
        if (collectTime > 0) {
            putIfAbsent(result, ProcessResultMetadataKeys.COLLECT_TIME, collectTime);
        }
        putIfAbsent(result, ProcessResultMetadataKeys.SOURCE, source);
    }

    private void putIfAbsent(ProcessResult result, String key, Object value) {
        if (result.getMetadata() == null || key == null || value == null) {
            return;
        }
        result.getMetadata().putIfAbsent(key, value);
    }
}
