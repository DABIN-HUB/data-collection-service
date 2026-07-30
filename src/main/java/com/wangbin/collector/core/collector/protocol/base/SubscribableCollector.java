package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.List;

public interface SubscribableCollector {

    void subscribe(List<DataPoint> points) throws CollectorException;

    void unsubscribe(List<DataPoint> points) throws CollectorException;
}
