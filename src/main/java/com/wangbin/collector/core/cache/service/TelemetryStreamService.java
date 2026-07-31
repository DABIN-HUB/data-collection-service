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
}

