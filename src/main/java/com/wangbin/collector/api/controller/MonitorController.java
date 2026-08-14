package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.ConsoleRuntimeStatusApplicationService;
import com.wangbin.collector.api.controller.dto.CloudReportMetricsResponse;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMonitorService;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.monitor.metrics.CollectorMetrics;
import com.wangbin.collector.monitor.metrics.ConsoleRuntimeStatusSnapshot;
import com.wangbin.collector.monitor.metrics.DeviceMonitorService;
import com.wangbin.collector.monitor.metrics.DeviceStatusSnapshot;
import com.wangbin.collector.monitor.metrics.ExceptionMonitorService;
import com.wangbin.collector.monitor.metrics.ExceptionStatsSnapshot;
import com.wangbin.collector.monitor.metrics.PerformanceMonitorService;
import com.wangbin.collector.monitor.metrics.StorageMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.SystemResourceSnapshot;
import com.wangbin.collector.monitor.metrics.TdengineMonitorService;
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
    private final ConsoleRuntimeStatusApplicationService consoleRuntimeStatusApplicationService;

    /**
     * 查询控制台运行状态统一快照。
     *
     * @return 控制台运行状态统一快照
     */
    @GetMapping("/runtime")
    public ConsoleRuntimeStatusSnapshot runtimeStatus() {
        return consoleRuntimeStatusApplicationService.getRuntimeStatus();
    }

    /**
     * 查询缓存指标快照。
     *
     * @return 缓存指标快照
     */
    @GetMapping("/cache")
    public CacheMetricsSnapshot cacheMetrics() {
        return cacheMonitorService.getCacheMetrics();
    }

    /**
     * 查询设备连接指标快照。
     *
     * @return 设备连接指标快照
     */
    @GetMapping("/devices")
    public DeviceStatusSnapshot deviceStatus() {
        return deviceMonitorService.getDeviceStatus();
    }

    /**
     * 查询采集性能指标列表。
     *
     * @return 采集性能指标列表
     */
    @GetMapping("/performance")
    public List<CollectorMetrics> collectorPerformance() {
        return performanceMonitorService.getCollectorMetrics();
    }

    /**
     * 查询系统资源指标快照。
     *
     * @return 系统资源指标快照
     */
    @GetMapping("/system")
    public SystemResourceSnapshot systemResources() {
        return systemResourceMonitorService.getResources();
    }

    /**
     * 查询异常统计快照。
     *
     * @return 异常统计快照
     */
    @GetMapping("/errors")
    public ExceptionStatsSnapshot exceptionStats() {
        return exceptionMonitorService.getStats();
    }

    /**
     * 查询云端上报链路指标。
     *
     * @return 云端上报链路指标
     */
    @GetMapping("/report")
    public CloudReportMetricsResponse cloudReportMetrics() {
        return CloudReportMetricsResponse.from(cloudReportMonitorService.getCloudReportMetrics());
    }

    /**
     * 查询历史存储指标。
     *
     * @return 历史存储指标
     */
    @GetMapping("/storage")
    public StorageMetricsSnapshot storageMetrics() {
        return tdengineMonitorService.getStorageMetrics();
    }

    /**
     * 查询调度器性能详情。
     *
     * @return 调度器性能详情
     */
    @GetMapping("/perf/detail")
    public PerformanceStatsSnapshot performanceDetail() {
        return collectionScheduler.getPerformanceSnapshot();
    }
}
