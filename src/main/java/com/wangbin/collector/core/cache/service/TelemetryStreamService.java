package com.wangbin.collector.core.cache.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

public interface TelemetryStreamService {

    void append(String deviceId, DataPoint point, ProcessResult processResult);
}

