package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

public interface WritableCollector {

    boolean writePoint(DataPoint point, Object value) throws CollectorException;

    Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException;
}
