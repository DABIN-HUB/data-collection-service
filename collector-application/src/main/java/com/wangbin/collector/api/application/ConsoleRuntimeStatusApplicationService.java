package com.wangbin.collector.api.application;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.cache.constant.CacheMetricKeys;
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
import org.springframework.util.StringUtils;

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

        SnapshotRead<CacheMetricsSnapshot> cache = readSnapshot("缓存系统", cacheMonitorService::getCacheMetrics);
        SnapshotRead<DeviceStatusSnapshot> devices = readSnapshot("设备连接", deviceMonitorService::getDeviceStatus);
        SnapshotRead<SystemResourceSnapshot> system = readSnapshot("系统资源", systemResourceMonitorService::getResources);
        SnapshotRead<ExceptionStatsSnapshot> exceptions = readSnapshot("异常统计", exceptionMonitorService::getStats);
        SnapshotRead<PerformanceStatsSnapshot> performance = readSnapshot("采集调度", collectionScheduler::getPerformanceSnapshot);
        SnapshotRead<Map<String, Object>> report = readSnapshot("云端上报", cloudReportMonitorService::getCloudReportMetrics);
        SnapshotRead<StorageMetricsSnapshot> storage = readSnapshot("历史存储", tdengineMonitorService::getStorageMetrics);

        components.add(evaluateCache(cache, risks));
        components.add(evaluateDevices(devices, risks));
        components.add(evaluateSystem(system, risks));
        components.add(evaluateExceptions(exceptions, risks));
        components.add(evaluatePerformance(performance, risks));
        components.add(evaluateReport(report, risks));
        components.add(evaluateStorage(storage, risks));

        RuntimeHealthLevel level = resolveOverallLevel(components);
        return ConsoleRuntimeStatusSnapshot.builder()
                .level(level)
                .message(overallMessage(level))
                .components(components)
                .risks(risks)
                .cache(cache.value())
                .devices(devices.value())
                .system(system.value())
                .exceptions(exceptions.value())
                .performance(performance.value())
                .report(report.value() == null ? Map.of() : report.value())
                .storage(storage.value())
                .build();
    }

    /**
     * 安全读取单个监控快照。
     */
    private <T> SnapshotRead<T> readSnapshot(String name, Supplier<T> supplier) {
        try {
            return new SnapshotRead<>(supplier.get(), false, null);
        } catch (RuntimeException exception) {
            log.error("读取运行状态失败，组件={}", name, exception);
            return new SnapshotRead<>(null, true, readFailureMessage(name, exception));
        }
    }

    /**
     * 评估缓存系统状态。
     */
    private RuntimeComponentStatus evaluateCache(SnapshotRead<CacheMetricsSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("cache-health", "缓存系统健康", read, risks);
        }
        CacheMetricsSnapshot snapshot = read.value();
        if (snapshot == null) {
            return component("cache-health", "缓存系统健康", RuntimeHealthLevel.UNKNOWN,
                    "缓存指标不可用", Map.of());
        }

        Map<String, Object> health = snapshot.getHealth() == null ? Map.of() : snapshot.getHealth();
        Object rawStatus = health.get(CacheMetricKeys.OVERALL_STATUS);
        RuntimeHealthLevel level = cacheLevel(rawStatus);
        String message = switch (level) {
            case OK -> "缓存系统状态正常";
            case WARN -> "缓存系统存在风险";
            case ERROR -> "缓存系统存在明确异常";
            case DISABLED -> "缓存系统未启用";
            case UNKNOWN -> "缓存健康状态未知";
        };
        if (level == RuntimeHealthLevel.WARN || level == RuntimeHealthLevel.ERROR) {
            addRisk(risks, message);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(CacheMetricKeys.OVERALL_STATUS, rawStatus);
        details.put("health", health);
        return component("cache-health", "缓存系统健康", level, message, details);
    }

    /**
     * 评估设备连接状态。
     */
    private RuntimeComponentStatus evaluateDevices(SnapshotRead<DeviceStatusSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("devices-health", "设备连接健康", read, risks);
        }
        DeviceStatusSnapshot snapshot = read.value();
        if (snapshot == null) {
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.UNKNOWN,
                    "设备连接状态不可用", Map.of());
        }
        List<String> missingConnections = snapshot.getMissingConnections() == null ? List.of() : snapshot.getMissingConnections();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalConnections", snapshot.getTotalConnections());
        details.put("activeConnections", snapshot.getActiveConnections());
        details.put("expectedConnections", snapshot.getExpectedConnections());
        details.put("dangerDevices", snapshot.getDangerDevices());
        details.put("warningDevices", snapshot.getWarningDevices());
        details.put("missingConnections", missingConnections);

        if (snapshot.getDangerDevices() > 0 || !missingConnections.isEmpty()) {
            String message = "存在设备连接异常";
            addRisk(risks, message);
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.ERROR, message, details);
        }
        if (snapshot.getWarningDevices() > 0) {
            String message = "存在设备连接风险";
            addRisk(risks, message);
            return component("devices-health", "设备连接健康", RuntimeHealthLevel.WARN, message, details);
        }
        return component("devices-health", "设备连接健康", RuntimeHealthLevel.OK, "设备连接状态正常", details);
    }

    /**
     * 评估系统资源状态。
     */
    private RuntimeComponentStatus evaluateSystem(SnapshotRead<SystemResourceSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("system-health", "系统资源健康", read, risks);
        }
        SystemResourceSnapshot snapshot = read.value();
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

        boolean cpuUnavailable = metricUnavailable(cpuLoad);
        boolean memoryUnavailable = metricUnavailable(memoryUsage);

        if (cpuLoad >= CPU_ERROR_THRESHOLD || memoryUsage >= MEMORY_ERROR_THRESHOLD
                || snapshot.getOutboxIsolatedCount() > 0) {
            String message = "系统资源存在明确异常";
            addRisk(risks, message);
            return component("system-health", "系统资源健康", RuntimeHealthLevel.ERROR, message, details);
        }
        if (cpuLoad >= CPU_WARN_THRESHOLD || memoryUsage >= MEMORY_WARN_THRESHOLD
                || snapshot.getOutboxPendingCount() > 0) {
            String message = "系统资源存在风险";
            addRisk(risks, message);
            return component("system-health", "系统资源健康", RuntimeHealthLevel.WARN, message, details);
        }
        if (cpuUnavailable || memoryUnavailable) {
            return component("system-health", "系统资源健康", RuntimeHealthLevel.UNKNOWN,
                    "系统资源指标不完整", details);
        }
        return component("system-health", "系统资源健康", RuntimeHealthLevel.OK, "系统资源状态正常", details);
    }

    /**
     * 评估异常统计状态。
     */
    private RuntimeComponentStatus evaluateExceptions(SnapshotRead<ExceptionStatsSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("exceptions-health", "异常统计健康", read, risks);
        }
        ExceptionStatsSnapshot snapshot = read.value();
        if (snapshot == null) {
            return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.UNKNOWN,
                    "异常统计不可用", Map.of());
        }
        int recentCount = snapshot.getRecent() == null ? 0 : snapshot.getRecent().size();
        Map<String, Object> details = Map.of(
                "totalExceptions", snapshot.getTotalExceptions(),
                "recentCount", recentCount
        );
        if (snapshot.getTotalExceptions() > 0) {
            String message = "存在采集或系统异常记录";
            addRisk(risks, message);
            return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.WARN, message, details);
        }
        return component("exceptions-health", "异常统计健康", RuntimeHealthLevel.OK, "暂无异常记录", details);
    }

    /**
     * 评估采集调度性能快照读取状态。
     */
    private RuntimeComponentStatus evaluatePerformance(SnapshotRead<PerformanceStatsSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("performance-health", "采集调度健康", read, risks);
        }
        PerformanceStatsSnapshot snapshot = read.value();
        if (snapshot == null) {
            return component("performance-health", "采集调度健康", RuntimeHealthLevel.UNKNOWN,
                    "采集调度状态不可用", Map.of());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("timeSliceCount", snapshot.getTimeSliceCount());
        details.put("timeSliceIntervalMs", snapshot.getTimeSliceIntervalMs());
        details.put("batchDispatchRejectedCount", snapshot.getBatchDispatchRejectedCount());
        details.put("collectRejectedCount", snapshot.getCollectRejectedCount());
        details.put("processRejectedCount", snapshot.getProcessRejectedCount());
        return component("performance-health", "采集调度健康", RuntimeHealthLevel.OK,
                "采集调度指标可用", details);
    }

    /**
     * 评估云端上报状态。
     */
    private RuntimeComponentStatus evaluateReport(SnapshotRead<Map<String, Object>> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("report-health", "云端上报健康", read, risks);
        }
        Map<String, Object> report = read.value();
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
            addRisk(risks, message);
        }
        return component("report-health", "云端上报健康", level, message, Map.of(CommonMapKeys.STATUS, status));
    }

    /**
     * 评估历史存储状态。
     */
    private RuntimeComponentStatus evaluateStorage(SnapshotRead<StorageMetricsSnapshot> read, List<String> risks) {
        if (read.failed()) {
            return failedComponent("storage-health", "历史存储健康", read, risks);
        }
        StorageMetricsSnapshot snapshot = read.value();
        if (snapshot == null) {
            return component("storage-health", "历史存储健康", RuntimeHealthLevel.UNKNOWN,
                    "历史存储状态不可用", Map.of());
        }
        RuntimeHealthLevel level = storageLevel(snapshot.getStatus());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", snapshot.isEnabled());
        details.put("status", snapshot.getStatus());
        details.put("responseTimeMs", snapshot.getResponseTimeMs());
        String message = storageMessage(snapshot.getMessage(), level);
        if (level == RuntimeHealthLevel.ERROR) {
            addRisk(risks, message);
        }
        return component("storage-health", "历史存储健康", level, message, details);
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
     * 读取失败时构建对应健康组件。
     */
    private RuntimeComponentStatus failedComponent(String code,
                                                   String name,
                                                   SnapshotRead<?> read,
                                                   List<String> risks) {
        addRisk(risks, read.errorMessage());
        return component(code, name, RuntimeHealthLevel.ERROR, read.errorMessage(), Map.of());
    }

    /**
     * 构建读取失败说明。
     */
    private String readFailureMessage(String name, RuntimeException exception) {
        String reason = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
        return name + "指标读取失败: " + reason;
    }

    /**
     * 追加风险说明，过滤空文本并保持顺序去重。
     */
    private void addRisk(List<String> risks, String message) {
        if (!StringUtils.hasText(message)) {
            return;
        }
        String normalized = message.trim();
        if (!risks.contains(normalized)) {
            risks.add(normalized);
        }
    }

    /**
     * 判断指标是否不可用。
     */
    private boolean metricUnavailable(double value) {
        return Double.isNaN(value) || value < 0.0D;
    }

    /**
     * 映射缓存健康状态。
     */
    private RuntimeHealthLevel cacheLevel(Object rawStatus) {
        if (rawStatus == null) {
            return RuntimeHealthLevel.UNKNOWN;
        }
        String status = String.valueOf(rawStatus).trim().toUpperCase();
        return switch (status) {
            case "OK", "UP", "READY", "HEALTHY", "RUNNING" -> RuntimeHealthLevel.OK;
            case "WARN", "WARNING", "DEGRADED" -> RuntimeHealthLevel.WARN;
            case "ERROR", "DOWN", "FAILED", "UNHEALTHY", "CRITICAL" -> RuntimeHealthLevel.ERROR;
            case "DISABLED" -> RuntimeHealthLevel.DISABLED;
            default -> RuntimeHealthLevel.UNKNOWN;
        };
    }

    /**
     * 映射历史存储健康状态。
     */
    private RuntimeHealthLevel storageLevel(StorageMetricsSnapshot.Status status) {
        if (status == null) {
            return RuntimeHealthLevel.UNKNOWN;
        }
        return switch (status) {
            case OK -> RuntimeHealthLevel.OK;
            case ERROR -> RuntimeHealthLevel.ERROR;
            case DISABLED -> RuntimeHealthLevel.DISABLED;
            case UNKNOWN -> RuntimeHealthLevel.UNKNOWN;
        };
    }

    /**
     * 构建历史存储健康说明。
     */
    private String storageMessage(String message, RuntimeHealthLevel level) {
        if (StringUtils.hasText(message)) {
            return message;
        }
        return switch (level) {
            case OK -> "历史存储状态正常";
            case WARN -> "历史存储存在风险";
            case ERROR -> "历史存储状态异常";
            case DISABLED -> "历史存储未启用";
            case UNKNOWN -> "历史存储状态未知";
        };
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

    /**
     * 监控快照读取结果。
     *
     * @param value 读取到的原始快照
     * @param failed 是否读取失败
     * @param errorMessage 读取失败说明
     */
    private record SnapshotRead<T>(T value, boolean failed, String errorMessage) {
    }
}
