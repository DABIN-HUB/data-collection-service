package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface ReadableCollector {

    /**
     * 查询并返回业务数据。
     */
    Object readPoint(DataPoint point) throws CollectorException;

    /**
     * 查询并返回业务数据。
     */
    Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException;
}
