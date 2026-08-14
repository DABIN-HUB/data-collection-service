package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface WritableCollector {

    /**
     * 写入或持久化业务数据。
     */
    boolean writePoint(DataPoint point, Object value) throws CollectorException;

    /**
     * 写入或持久化业务数据。
     */
    Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException;
}
