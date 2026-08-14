package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 采集数据处理器：负责采集结果后的数据处理和自适应频率更新。
 */
@Component
public class CollectedDataProcessor {

    private final CollectorProperties collectorProperties;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final PerformanceMonitor performanceMonitor;

    /**
     * 创建当前组件实例。
     */
    public CollectedDataProcessor(CollectorProperties collectorProperties,
                                  PointRuntimeStateService pointRuntimeStateService,
                                  PerformanceMonitor performanceMonitor) {
        this.collectorProperties = collectorProperties;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.performanceMonitor = performanceMonitor;
    }

    void process(String deviceId,
                 List<DataPoint> points,
                 Map<String, Object> values) {
        for (DataPoint point : points) {
            String pointId = point.getPointId();
            Object value = values.get(pointId);
            if (value == null) {
                continue;
            }

            performanceMonitor.recordDataProcessed(deviceId);
            DevicePerformance perf = performanceMonitor.devicePerformance.get(deviceId);
            if (perf != null && perf.consecutiveFailureCount > 0) {
                pointRuntimeStateService.reset(deviceId, point);
            }

            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                pointRuntimeStateService.adjust(
                        deviceId,
                        point,
                        value,
                        collectorProperties.getAdaptiveCollection().getAdjustWindowMs());
            }
        }
    }
}

