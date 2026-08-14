package com.wangbin.collector.core.cache.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

/**
 * 定义当前模块的业务契约。
 */
public interface TelemetryStreamService {

    /**
     * 写入或持久化业务数据。
     */
    void append(String deviceId, DataPoint point, ProcessResult processResult);

    /**
     * 尝试进入 Redis Stream best-effort 写入路径，返回是否成功接收。
     */
    default boolean appendBestEffort(String deviceId, DataPoint point, ProcessResult processResult) {
        append(deviceId, point, processResult);
        return true;
    }

    /**
     * 返回 Redis Stream 写入路径的内部观测快照。
     */
    default TelemetryStreamMetrics metrics() {
        return TelemetryStreamMetrics.empty();
    }
}

