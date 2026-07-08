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
    private final CollectionScheduler collectionScheduler;

    @GetMapping("/cache")
    public CacheMetricsSnapshot cacheMetrics() {
        return cacheMonitorService.getCacheMetrics();
    }

    @GetMapping("/devices")
    public DeviceStatusSnapshot deviceStatus() {
        return deviceMonitorService.getDeviceStatus();
    }

    @GetMapping("/performance")
    public List<CollectorMetrics> collectorPerformance() {
        return performanceMonitorService.getCollectorMetrics();
    }

    @GetMapping("/system")
    public SystemResourceSnapshot systemResources() {
        return systemResourceMonitorService.getResources();
    }

    @GetMapping("/errors")
    public ExceptionStatsSnapshot exceptionStats() {
        return exceptionMonitorService.getStats();
    }

    @GetMapping("/report")
    public Map<String, Object> cloudReportMetrics() {
        return cloudReportMonitorService.getCloudReportMetrics();
    }

    @GetMapping("/perf/detail")
    public PerformanceStatsSnapshot performanceDetail() {
        return collectionScheduler.getPerformanceSnapshot();
    }
}
