package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.List;

/**
 * 允许协议采集器声明调度前需要筛选轮询和自动订阅点位。
 */
public interface ProtocolPointSelectionSupport {

    /**
     * 返回需要进入轮询调度的点位。
     */
    List<DataPoint> filterPollingPoints(List<DataPoint> points);

    /**
     * 返回设备启动时需要自动订阅的点位。
     */
    List<DataPoint> filterAutoSubscriptionPoints(List<DataPoint> points);
}
