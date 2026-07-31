package com.wangbin.collector.api.controller;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import com.wangbin.collector.monitor.metrics.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 监控相关接口。
 */
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final CacheMonitorService cacheMonitorService;
    private final DeviceMonitorService deviceMonitorService;
    private final PerformanceMonitorService performanceMonitorService;
    private final SystemResourceMonitorService systemResourceMonitorService;
    private final ExceptionMonitorService exceptionMonitorService;
    private final CloudReportMonitorService cloudReportMonitorService;
    private final TdengineMonitorService tdengineMonitorService;
    private final CollectionScheduler collectionScheduler;

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/cache")
    public CacheMetricsSnapshot cacheMetrics() {
        return cacheMonitorService.getCacheMetrics();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/devices")
    public DeviceStatusSnapshot deviceStatus() {
        return deviceMonitorService.getDeviceStatus();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/performance")
    public List<CollectorMetrics> collectorPerformance() {
        return performanceMonitorService.getCollectorMetrics();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/system")
    public SystemResourceSnapshot systemResources() {
        return systemResourceMonitorService.getResources();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/errors")
    public ExceptionStatsSnapshot exceptionStats() {
        return exceptionMonitorService.getStats();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/report")
    public Map<String, Object> cloudReportMetrics() {
        return cloudReportMonitorService.getCloudReportMetrics();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/storage")
    public StorageMetricsSnapshot storageMetrics() {
        return tdengineMonitorService.getStorageMetrics();
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/perf/detail")
    public PerformanceStatsSnapshot performanceDetail() {
        return collectionScheduler.getPerformanceSnapshot();
    }
}
