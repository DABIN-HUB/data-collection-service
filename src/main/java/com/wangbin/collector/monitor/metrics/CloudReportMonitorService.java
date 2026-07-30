package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cloud.model.CloudTargetConfig;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.service.ReportManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 云上报链路聚合监控服务。
 */
@Slf4j
@Service
public class CloudReportMonitorService {

    private static final double QUEUE_WARN_THRESHOLD = 0.70D;
    private static final double QUEUE_ERROR_THRESHOLD = 0.90D;

    private final ReportProperties reportProperties;
    private final ReportManager reportManager;
    private final ConfigManager configManager;
    private final Executor reportExecutor;

    public CloudReportMonitorService(ReportProperties reportProperties,
                                     ReportManager reportManager,
                                     ConfigManager configManager,
                                     @Qualifier("reportExecutor") Executor reportExecutor) {
        this.reportProperties = reportProperties;
        this.reportManager = reportManager;
        this.configManager = configManager;
        this.reportExecutor = reportExecutor;
    }

    public Map<String, Object> getCloudReportMetrics() {
        Map<String, Object> configured = collectConfiguredMetrics();
        Map<String, Object> executor = inspectReportExecutor();
        List<String> risks = collectRisks(configured, executor);
        String status = resolveStatus(executor, risks);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", reportProperties.isEnabled());
        result.put("status", status);
        result.put("statusText", statusText(status));
        result.put("mode", reportProperties.getMode());
        result.put("cloudProvider", reportProperties.getMqtt().getCloudProvider());
        result.put("supportedProtocols", safeList(reportManager.getSupportedProtocols()));
        result.put("handlersStatus", safeMap(reportManager.getHandlersStatus()));
        result.put("handlersStatistics", safeMap(reportManager.getHandlersStatistics()));
        result.put("configured", configured);
        result.put("executor", executor);
        result.put("batch", batchOptions());
        result.put("ack", ackOptions());
        result.put("payload", payloadOptions());
        result.put("risks", risks);
        result.put("generatedAt", System.currentTimeMillis());
        return result;
    }

    private Map<String, Object> collectConfiguredMetrics() {
        List<DeviceContext> contexts = safeContexts();
        int deviceCount = contexts.size();
        int pointCount = 0;
        int reportEnabledPointCount = 0;
        int eventEnabledPointCount = 0;
        int changeTriggerPointCount = 0;
        int reportFieldPointCount = 0;
        int reportablePointCount = 0;
        int cloudTargetDeviceCount = 0;
        int invalidCloudTargetDeviceCount = 0;
        Set<String> cloudTargetKeys = new LinkedHashSet<>();

        for (DeviceContext context : contexts) {
            List<DataPoint> points = context.getDataPoints() == null ? Collections.emptyList() : context.getDataPoints();
            CloudTargetConfig cloudTarget = context.getDeviceInfo() != null ? context.getDeviceInfo().getCloudTarget() : null;
            boolean validCloudTarget = cloudTarget != null && cloudTarget.valid();
            if (validCloudTarget) {
                cloudTargetDeviceCount++;
                cloudTargetKeys.add(cloudTarget.identity().key());
            } else if (cloudTarget != null && cloudTarget.isEnabled()) {
                invalidCloudTargetDeviceCount++;
            }
            for (DataPoint point : points) {
                if (point == null) {
                    continue;
                }
                pointCount++;
                if (point.isReportEnabled()) {
                    reportEnabledPointCount++;
                    reportFieldPointCount++;
                }
                if (point.isReportEnabled() && point.isEventReportingEnabled()) {
                    eventEnabledPointCount++;
                }
                if (point.isChangeTriggerEnabled()) {
                    changeTriggerPointCount++;
                }
                if (point.isReportEnabled() && validCloudTarget) {
                    reportablePointCount++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCount", deviceCount);
        result.put("pointCount", pointCount);
        result.put("reportEnabledPointCount", reportEnabledPointCount);
        result.put("eventEnabledPointCount", eventEnabledPointCount);
        result.put("changeTriggerPointCount", changeTriggerPointCount);
        result.put("reportFieldPointCount", reportFieldPointCount);
        result.put("reportablePointCount", reportablePointCount);
        result.put("cloudTargetDeviceCount", cloudTargetDeviceCount);
        result.put("invalidCloudTargetDeviceCount", invalidCloudTargetDeviceCount);
        result.put("cloudTargetCount", cloudTargetKeys.size());
        result.put("cloudTargetKeys", new ArrayList<>(cloudTargetKeys));
        result.put("cloudTargetCoverage", pointCount > 0 ? (double) reportablePointCount / pointCount : 0.0D);
        return result;
    }

    private List<DeviceContext> safeContexts() {
        try {
            List<DeviceContext> contexts = configManager.getAllDeviceContexts();
            return contexts == null ? Collections.emptyList() : contexts;
        } catch (RuntimeException e) {
            log.warn("读取云上报配置快照失败", e);
            return Collections.emptyList();
        }
    }

    private Map<String, Object> inspectReportExecutor() {
        if (reportExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
            ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
            return executor == null ? emptyExecutorMetrics("ThreadPoolTaskExecutor")
                    : buildExecutorMetrics("ThreadPoolTaskExecutor", executor);
        }
        if (reportExecutor instanceof ThreadPoolExecutor executor) {
            return buildExecutorMetrics("ThreadPoolExecutor", executor);
        }
        return emptyExecutorMetrics(reportExecutor == null ? "unknown" : reportExecutor.getClass().getSimpleName());
    }

    private Map<String, Object> buildExecutorMetrics(String type, ThreadPoolExecutor executor) {
        BlockingQueue<Runnable> queue = executor.getQueue();
        int queueSize = queue == null ? -1 : queue.size();
        int remainingCapacity = queue == null ? -1 : queue.remainingCapacity();
        int queueCapacity = queueSize >= 0 && remainingCapacity >= 0 ? queueSize + remainingCapacity : -1;
        double queueUsage = queueCapacity > 0 ? (double) queueSize / queueCapacity : 0.0D;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("corePoolSize", executor.getCorePoolSize());
        result.put("maxPoolSize", executor.getMaximumPoolSize());
        result.put("poolSize", executor.getPoolSize());
        result.put("activeCount", executor.getActiveCount());
        result.put("queueSize", queueSize);
        result.put("queueRemainingCapacity", remainingCapacity);
        result.put("queueCapacity", queueCapacity);
        result.put("queueUsage", queueUsage);
        result.put("completedTaskCount", executor.getCompletedTaskCount());
        result.put("taskCount", executor.getTaskCount());
        result.put("rejectedCount", rejectedCount(executor));
        return result;
    }

    private Map<String, Object> emptyExecutorMetrics(String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("corePoolSize", -1);
        result.put("maxPoolSize", -1);
        result.put("poolSize", -1);
        result.put("activeCount", -1);
        result.put("queueSize", -1);
        result.put("queueRemainingCapacity", -1);
        result.put("queueCapacity", -1);
        result.put("queueUsage", 0.0D);
        result.put("completedTaskCount", -1L);
        result.put("taskCount", -1L);
        result.put("rejectedCount", -1L);
        return result;
    }

    private long rejectedCount(ThreadPoolExecutor executor) {
        if (executor.getRejectedExecutionHandler() instanceof ObservedRejectedExecutionHandler observed) {
            return observed.getRejectedCount();
        }
        return -1L;
    }

    private List<String> collectRisks(Map<String, Object> configured, Map<String, Object> executor) {
        List<String> risks = new ArrayList<>();
        if (!reportProperties.isEnabled()) {
            risks.add("云上报总开关未启用");
            return risks;
        }
        if (reportManager.getSupportedProtocols() == null || reportManager.getSupportedProtocols().isEmpty()) {
            risks.add("未发现可用上报协议处理器");
        }
        if (number(configured.get("reportFieldPointCount")) <= 0) {
            risks.add("未配置启用上报且包含 reportField 的点位，云端属性上报无数据来源");
        }
        if (number(configured.get("invalidCloudTargetDeviceCount")) > 0) {
            risks.add("存在启用云上报但缺少有效 cloudTarget 的采集设备");
        }
        if (number(configured.get("reportFieldPointCount")) > number(configured.get("reportablePointCount"))) {
            risks.add("存在已配置 reportField 但所属设备缺少有效 cloudTarget 的点位");
        }
        if (number(executor.get("queueUsage")) >= QUEUE_WARN_THRESHOLD) {
            risks.add("上报线程池队列水位偏高");
        }
        if (number(executor.get("rejectedCount")) > 0) {
            risks.add("上报线程池发生过拒绝任务");
        }
        return risks;
    }

    private String resolveStatus(Map<String, Object> executor, List<String> risks) {
        if (!reportProperties.isEnabled()) {
            return "DISABLED";
        }
        if (reportManager.getSupportedProtocols() == null || reportManager.getSupportedProtocols().isEmpty()) {
            return "ERROR";
        }
        if (number(executor.get("queueUsage")) >= QUEUE_ERROR_THRESHOLD) {
            return "ERROR";
        }
        if (!risks.isEmpty()) {
            return "WARN";
        }
        return "OK";
    }

    private String statusText(String status) {
        return switch (status) {
            case "OK" -> "云上报链路正常";
            case "WARN" -> "云上报链路存在风险";
            case "ERROR" -> "云上报链路异常";
            case "DISABLED" -> "云上报未启用";
            default -> "云上报状态未知";
        };
    }

    private Map<String, Object> batchOptions() {
        ReportProperties.Cloud.Batch batch = reportProperties.getCloud().getBatch();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", batch.isEnabled());
        result.put("maxDevicesPerPack", batch.getMaxDevicesPerPack());
        result.put("maxPropertiesPerPack", batch.getMaxPropertiesPerPack());
        result.put("maxPayloadBytes", batch.getMaxPayloadBytes());
        result.put("maxDelayMs", batch.getMaxDelayMs());
        result.put("highPriorityBypass", batch.isHighPriorityBypass());
        return result;
    }

    private Map<String, Object> ackOptions() {
        ReportProperties.Cloud.Ack ack = reportProperties.getCloud().getAck();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", ack.getMode());
        result.put("timeoutMs", ack.getTimeoutMs());
        result.put("maxPending", ack.getMaxPending());
        result.put("timeoutScanMs", ack.getTimeoutScanMs());
        result.put("commitOn", ack.getCommitOn());
        return result;
    }

    private Map<String, Object> payloadOptions() {
        ReportProperties.Cloud.Payload payload = reportProperties.getCloud().getPayload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", payload.getProfile());
        result.put("includeQuality", payload.getIncludeQuality());
        result.put("includePropertyTs", payload.isIncludePropertyTs());
        result.put("includeMetadata", payload.isIncludeMetadata());
        result.put("includeMessageId", payload.isIncludeMessageId());
        return result;
    }

    private List<String> safeList(List<String> source) {
        return source == null ? Collections.emptyList() : new ArrayList<>(source);
    }

    private Map<String, Map<String, Object>> safeMap(Map<String, Map<String, Object>> source) {
        return source == null ? Collections.emptyMap() : new LinkedHashMap<>(source);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }
}
