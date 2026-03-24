package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.CollectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 采集数据处理器：负责采集结果后的数据处理和自适应频率更新。
 */
@Component
@RequiredArgsConstructor
class CollectedDataProcessor {

    private final CollectorProperties collectorProperties;

    void process(String deviceId,
                 List<DataPoint> points,
                 Map<String, Object> values,
                 PerformanceMonitor performanceMonitor) {
        for (DataPoint point : points) {
            String pointId = point.getPointId();
            Object value = values.get(pointId);
            if (value == null) {
                continue;
            }

            performanceMonitor.recordDataProcessed(deviceId);
            DevicePerformance perf = performanceMonitor.devicePerformance.get(deviceId);
            if (perf != null && perf.consecutiveFailureCount > 0) {
                AdaptiveCollectionUtil.resetAdaptiveConfig(point);
            }

            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                AdaptiveCollectionUtil.adjustCollectionFrequency(
                        deviceId,
                        point,
                        value,
                        collectorProperties.getAdaptiveCollection().getAdjustWindowMs());
            }
        }
    }
}

