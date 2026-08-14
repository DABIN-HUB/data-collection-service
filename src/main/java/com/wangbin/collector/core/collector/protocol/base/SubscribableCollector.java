package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.List;

/**
 * 定义当前模块的业务契约。
 */
public interface SubscribableCollector {

    /**
     * 维护注册或订阅关系。
     */
    void subscribe(List<DataPoint> points) throws CollectorException;

    /**
     * 维护注册或订阅关系。
     */
    void unsubscribe(List<DataPoint> points) throws CollectorException;
}
