package com.wangbin.collector.api.application;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.CacheMonitorService;
import com.wangbin.collector.monitor.metrics.CloudReportMetricKeys;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.monitor.metrics.ConsoleRuntimeStatusSnapshot;
import com.wangbin.collector.monitor.metrics.DeviceStatusSnapshot;
import com.wangbin.collector.monitor.metrics.DeviceMonitorService;
import com.wangbin.collector.monitor.metrics.ExceptionMonitorService;
import com.wangbin.collector.monitor.metrics.ExceptionStatsSnapshot;
import com.wangbin.collector.monitor.metrics.RuntimeComponentStatus;
import com.wangbin.collector.monitor.metrics.RuntimeHealthLevel;
import com.wangbin.collector.monitor.metrics.StorageMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.SystemResourceSnapshot;
import com.wangbin.collector.monitor.metrics.TdengineMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 控制台运行状态聚合应用服务。
 *
 * <p>该服务只聚合现有监控事实，不改变采集、缓存、历史存储和上报执行逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleRuntimeStatusApplicationService {

    private static final double CPU_ERROR_THRESHOLD = 90.0D;
    private static final double CPU_WARN_THRESHOLD = 75.0D;
    private static final double MEMORY_ERROR_THRESHOLD = 90.0D;
    private static final double MEMORY_WARN_THRESHOLD = 80.0D;

    private final CacheMonitorService cacheMonitorService;
    private final DeviceMonitorService deviceMonitorService;
    private final SystemResourceMonitorService systemResourceMonitorService;
    private final ExceptionMonitorService exceptionMonitorService;
    private final CloudReportMonitorService cloudReportMonitorService;
    private final TdengineMonitorService tdengineMonitorService;
    private final CollectionScheduler collectionScheduler;

    /**
     * 构建控制台运行状态统一快照。
     *
     * @return 控制台运行状态统一快照
     */
    public ConsoleRuntimeStatusSnapshot getRuntimeStatus() {
        List<RuntimeComponentStatus> components = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        CacheMetricsSnapshot cache = readSnapshot("cache", "缓存系统", components, risks,
                cacheMonitorService::getCacheMetrics);
        DeviceStatusSnapshot devices = readSnapshot("devices", "设备连接", components, risks,
                deviceMonitorService::getDeviceStatus);
        SystemResourceSnapshot system = readSnapshot("system", "系统资源", components, risks,
                systemResourceMonitorService::getResources);
        ExceptionStatsSnapshot exceptions = readSnapshot("exceptions", "异常统计", components, risks,
                exceptionMonitorService::getStats);
        PerformanceStatsSnapshot performance = readSnapshot("performance", "采集调度", components, risks,
                collectionScheduler::getPerformanceSnapshot);
        Map<String, Object> report = readSnapshot("report", "云端上报", components, risks,
                cloudReportMonitorService::getCloudReportMetrics);
        StorageMetricsSnapshot storage = readSnapshot("storage", "历史存储", components, risks,
                tdengineMonitorService::getStorageMetrics);

        components.add(evaluateDevices(devices, risks));
        components.add(evaluateSystem(system, risks));
        components.add(evaluateExceptions(exceptions, risks));
        components.add(evaluateReport(report, risks));
        components.add(evaluateStorage(storage, risks));

        RuntimeHealthLevel level = resolveOverallLevel(components);
        return ConsoleRuntimeStatusSnapshot.builder()
                .level(level)
                .message(overallMessage(level))
                .components(components)
                .risks(risks)
                .cache(cache)
                .devices(devices)
                .system(system)
                .exceptions(exceptions)
                .performance(performance)
                .report(report == null ? Map.of() : report)
                .storage(storage)
                .build();
    }

    /**
     * 安全读取单个监控快照。
     */
    private <T> T readSnapshot(String code,
                               String name,
                               List<RuntimeComponentStatus> components,
                               List<String> risks,
                               Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException exception) {
            log.error("读取运行状态失败，组件={}", name, exception);
            String message = name + "指标读取失败: " + exception.getMessage();
            risks.add(message);
            components.add(component(code, name, RuntimeHealthLevel.ERROR, message, Map.of()));
            return null;
        }
    }

    /**
     * 评估设备连接状态。
     */
    private RuntimeComponentStatus evaluateDevices(DeviceStatusSnapshot snapshot, List<String> risks) {
        if (snapshot == null) {
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.UNKNOWN,
                    "设备连接状态不可用", Map.of());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalConnections", snapshot.getTotalConnections());
        details.put("activeConnections", snapshot.getActiveConnections());
        details.put("expectedConnections", snapshot.getExpectedConnections());
        details.put("dangerDevices", snapshot.getDangerDevices());
        details.put("warningDevices", snapshot.getWarningDevices());
        details.put("missingConnections", snapshot.getMissingConnections());

        if (snapshot.getDangerDevices() > 0 || !snapshot.getMissingConnections().isEmpty()) {
            String message = "存在设备连接异常";
            risks.add(message);
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.ERROR, message, details);
        }
        if (snapshot.getWarningDevices() > 0) {
            String message = "存在设备连接风险";
            risks.add(message);
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.WARN, message, details);
        }
        return component("devices-health", "设备连接健康", RuntimeHealthLevel.OK, "设备连接状态正常", details);
    }

    /**
     * 评估系统资源状态。
     */
    private RuntimeComponentStatus evaluateSystem(SystemResourceSnapshot snapshot, List<String> risks) {
        if (snapshot == null) {
            return component("system-health", "系统资源健康", RuntimeHealthLevel.UNKNOWN,
                    "系统资源状态不可用", Map.of());
        }
        double memoryUsage = heapUsage(snapshot);
        double cpuLoad = snapshot.getProcessCpuLoad();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("processCpuLoad", cpuLoad);
        details.put("heapUsage", memoryUsage);
        details.put("threadCount", snapshot.getThreadCount());
        details.put("outboxPendingCount", snapshot.getOutboxPendingCount());
        details.put("outboxIsolatedCount", snapshot.getOutboxIsolatedCount());

        if (cpuLoad >= CPU_ERROR_THRESHOLD || memoryUsage >= MEMORY_ERROR_THRESHOLD
                || snapshot.getOutboxIsolatedCount() > 0) {
            String message = "系统资源存在明确异常";
            risks.add(message);
            return component("system-health", "系统资源健康", RuntimeHealthLevel.ERROR, message, details);
        }
        if (cpuLoad >= CPU_WARN_THRESHOLD || memoryUsage >= MEMORY_WARN_THRESHOLD
                || snapshot.getOutboxPendingCount() > 0) {
            String message = "系统资源存在风险";
            risks.add(message);
            return component("system-health", "系统资源健康", RuntimeHealthLevel.WARN, message, details);
        }
        return component("system-health", "系统资源健康", RuntimeHealthLevel.OK, "系统资源状态正常", details);
    }

    /**
     * 评估异常统计状态。
     */
    private RuntimeComponentStatus evaluateExceptions(ExceptionStatsSnapshot snapshot, List<String> risks) {
        if (snapshot == null) {
            return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.UNKNOWN,
                    "异常统计不可用", Map.of());
        }
        Map<String, Object> details = Map.of(
                "totalExceptions", snapshot.getTotalExceptions(),
                "recentCount", snapshot.getRecent().size()
        );
        if (snapshot.getTotalExceptions() > 0) {
            String message = "存在采集或系统异常记录";
            risks.add(message);
            return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.WARN, message, details);
        }
        return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.OK, "暂无异常记录", details);
    }

    /**
     * 评估云端上报状态。
     */
    private RuntimeComponentStatus evaluateReport(Map<String, Object> report, List<String> risks) {
        if (report == null || report.isEmpty()) {
            return component("report-health", "云端上报健康", RuntimeHealthLevel.UNKNOWN,
                    "云端上报状态不可用", Map.of());
        }
        Object rawStatus = report.get(CommonMapKeys.STATUS);
        String status = rawStatus == null ? "UNKNOWN" : String.valueOf(rawStatus);
        RuntimeHealthLevel level = switch (status) {
            case "OK", "READY" -> RuntimeHealthLevel.OK;
            case "WARN" -> RuntimeHealthLevel.WARN;
            case "ERROR" -> RuntimeHealthLevel.ERROR;
            case "DISABLED" -> RuntimeHealthLevel.DISABLED;
            default -> RuntimeHealthLevel.UNKNOWN;
        };
        Object statusText = report.get(CloudReportMetricKeys.STATUS_TEXT);
        String message = statusText == null ? "云端上报状态未知" : String.valueOf(statusText);
        if (level == RuntimeHealthLevel.WARN || level == RuntimeHealthLevel.ERROR) {
            risks.add(message);
        }
        return component("report-health", "云端上报健康", level, message, Map.of(CommonMapKeys.STATUS, status));
    }

    /**
     * 评估历史存储状态。
     */
    private RuntimeComponentStatus evaluateStorage(StorageMetricsSnapshot snapshot, List<String> risks) {
        if (snapshot == null) {
            return component("storage-health", "历史存储健康", RuntimeHealthLevel.UNKNOWN,
                    "历史存储状态不可用", Map.of());
        }
        RuntimeHealthLevel level = switch (snapshot.getStatus()) {
            case OK -> RuntimeHealthLevel.OK;
            case ERROR -> RuntimeHealthLevel.ERROR;
            case DISABLED -> RuntimeHealthLevel.DISABLED;
            case UNKNOWN -> RuntimeHealthLevel.UNKNOWN;
        };
        Map<String, Object> details = Map.of(
                "enabled", snapshot.isEnabled(),
                "status", snapshot.getStatus(),
                "responseTimeMs", snapshot.getResponseTimeMs()
        );
        if (level == RuntimeHealthLevel.ERROR) {
            risks.add(snapshot.getMessage());
        }
        return component("storage-health", "历史存储健康", level, snapshot.getMessage(), details);
    }

    /**
     * 计算堆内存使用率。
     */
    private double heapUsage(SystemResourceSnapshot snapshot) {
        if (snapshot.getHeapMax() <= 0) {
            return -1.0D;
        }
        return (double) snapshot.getHeapUsed() * 100.0D / snapshot.getHeapMax();
    }

    /**
     * 解析整体健康级别。
     */
    private RuntimeHealthLevel resolveOverallLevel(List<RuntimeComponentStatus> components) {
        if (components.stream().anyMatch(item -> item.getLevel() == RuntimeHealthLevel.ERROR)) {
            return RuntimeHealthLevel.ERROR;
        }
        if (components.stream().anyMatch(item -> item.getLevel() == RuntimeHealthLevel.WARN)) {
            return RuntimeHealthLevel.WARN;
        }
        if (components.stream().anyMatch(item -> item.getLevel() == RuntimeHealthLevel.UNKNOWN)) {
            return RuntimeHealthLevel.UNKNOWN;
        }
        return RuntimeHealthLevel.OK;
    }

    /**
     * 构建整体状态说明。
     */
    private String overallMessage(RuntimeHealthLevel level) {
        return switch (level) {
            case OK -> "系统运行正常";
            case WARN -> "系统存在运行风险";
            case ERROR -> "系统存在明确异常";
            case DISABLED -> "系统能力未启用";
            case UNKNOWN -> "系统运行状态未知";
        };
    }

    /**
     * 构建组件状态。
     */
    private RuntimeComponentStatus component(String code,
                                             String name,
                                             RuntimeHealthLevel level,
                                             String message,
                                             Map<String, Object> details) {
        return RuntimeComponentStatus.builder()
                .code(code)
                .name(name)
                .level(level)
                .message(message)
                .details(details)
                .build();
    }
}
