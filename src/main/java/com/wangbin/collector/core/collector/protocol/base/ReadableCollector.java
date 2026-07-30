package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.List;
import java.util.Map;

public interface ReadableCollector {

    Object readPoint(DataPoint point) throws CollectorException;

    Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException;
}
