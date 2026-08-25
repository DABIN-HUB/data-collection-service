package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.util.List;

/**
 * 设备启动前准备结果。
 */
record StartPreparation(DeviceInfo deviceInfo, List<DataPoint> dataPoints, long generation, long connectTimeoutMs) {
}
