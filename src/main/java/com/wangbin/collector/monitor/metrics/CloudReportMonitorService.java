package com.wangbin.collector.monitor.metrics;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cloud.model.CloudTargetConfig;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
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
    private final CloudOutboxService cloudOutboxService;
    private final ConfigManager configManager;
    private final Executor reportExecutor;

    /**
     * 创建当前组件实例。
     */
    public CloudReportMonitorService(ReportProperties reportProperties,
                                     ReportManager reportManager,
                                     CloudOutboxService cloudOutboxService,
                                     ConfigManager configManager,
                                     @Qualifier("reportExecutor") Executor reportExecutor) {
        this.reportProperties = reportProperties;
        this.reportManager = reportManager;
        this.cloudOutboxService = cloudOutboxService;
        this.configManager = configManager;
        this.reportExecutor = reportExecutor;
    }

    public Map<String, Object> getCloudReportMetrics() {
        Map<String, Object> configured = collectConfiguredMetrics();
        Map<String, Object> executor = inspectReportExecutor();
        Map<String, Map<String, Object>> handlersStatus = safeMap(reportManager.getHandlersStatus());
        Map<String, Map<String, Object>> handlersStatistics = safeMap(reportManager.getHandlersStatistics());
        List<String> risks = collectRisks(configured, executor, handlersStatus, handlersStatistics);
        String status = resolveStatus(configured, executor, risks, handlersStatus, handlersStatistics);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.ENABLED, reportProperties.isEnabled());
        result.put(CommonMapKeys.STATUS, status);
        result.put("statusText", statusText(status));
        result.put(CommonMapKeys.MODE, reportProperties.getMode());
        result.put("cloudProvider", reportProperties.getMqtt().getCloudProvider());
        result.put("supportedProtocols", safeList(reportManager.getSupportedProtocols()));
        result.put("handlersStatus", handlersStatus);
        result.put("handlersStatistics", handlersStatistics);
        result.put("configured", configured);
        result.put("executor", executor);
        result.put("batch", batchOptions());
        result.put("ack", ackOptions());
        result.put("outbox", outboxMetrics());
        result.put("payload", payloadOptions());
        result.put("risks", risks);
        result.put("generatedAt", System.currentTimeMillis());
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
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
        result.put(CommonMapKeys.POINT_COUNT, pointCount);
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

    /**
     * 执行当前业务逻辑。
     */
    private List<DeviceContext> safeContexts() {
        try {
            List<DeviceContext> contexts = configManager.getAllDeviceContexts();
            return contexts == null ? Collections.emptyList() : contexts;
        } catch (RuntimeException e) {
            log.warn("读取云上报配置快照失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildExecutorMetrics(String type, ThreadPoolExecutor executor) {
        BlockingQueue<Runnable> queue = executor.getQueue();
        int queueSize = queue == null ? -1 : queue.size();
        int remainingCapacity = queue == null ? -1 : queue.remainingCapacity();
        int queueCapacity = queueSize >= 0 && remainingCapacity >= 0 ? queueSize + remainingCapacity : -1;
        double queueUsage = queueCapacity > 0 ? (double) queueSize / queueCapacity : 0.0D;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.TYPE, type);
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

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> emptyExecutorMetrics(String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.TYPE, type);
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

    /**
     * 执行当前业务逻辑。
     */
    private long rejectedCount(ThreadPoolExecutor executor) {
        if (executor.getRejectedExecutionHandler() instanceof ObservedRejectedExecutionHandler observed) {
            return observed.getRejectedCount();
        }
        return -1L;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<String> collectRisks(Map<String, Object> configured,
                                      Map<String, Object> executor,
                                      Map<String, Map<String, Object>> handlersStatus,
                                      Map<String, Map<String, Object>> handlersStatistics) {
        List<String> risks = new ArrayList<>();
        if (!reportProperties.isEnabled()) {
            risks.add("云上报总开关未启用");
            return risks;
        }
        if (reportManager.getSupportedProtocols() == null || reportManager.getSupportedProtocols().isEmpty()) {
            risks.add("未发现可用上报协议处理器");
        }
        Map<String, Object> activeHandler = activeHandler(handlersStatus);
        if (activeHandler.isEmpty()) {
            risks.add("当前上报模式没有对应的处理器");
        } else if (Boolean.FALSE.equals(activeHandler.get(CommonMapKeys.ENABLED))) {
            risks.add("当前上报处理器未启用");
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
        if (requiresPersistentConnection()
                && number(configured.get("cloudTargetDeviceCount")) > 0
                && !hasActiveTransport(handlersStatus, handlersStatistics)) {
            risks.add("当前上报模式没有已连接的云端会话");
        }
        return risks;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveStatus(Map<String, Object> configured,
                                 Map<String, Object> executor,
                                 List<String> risks,
                                 Map<String, Map<String, Object>> handlersStatus,
                                 Map<String, Map<String, Object>> handlersStatistics) {
        if (!reportProperties.isEnabled()) {
            return "DISABLED";
        }
        if (reportManager.getSupportedProtocols() == null
                || reportManager.getSupportedProtocols().isEmpty()
                || activeHandler(handlersStatus).isEmpty()) {
            return "ERROR";
        }
        if (number(executor.get("queueUsage")) >= QUEUE_ERROR_THRESHOLD) {
            return "ERROR";
        }
        if (requiresPersistentConnection()
                && number(configured.get("cloudTargetDeviceCount")) > 0
                && !hasActiveTransport(handlersStatus, handlersStatistics)) {
            return "ERROR";
        }
        if (!risks.isEmpty()) {
            return "WARN";
        }
        return requiresPersistentConnection() ? "OK" : "READY";
    }

    /**
     * 执行当前业务逻辑。
     */
    private String statusText(String status) {
        return switch (status) {
            case "OK" -> "云上报链路已连接";
            case "READY" -> "云上报配置就绪";
            case "WARN" -> "云上报链路存在风险";
            case "ERROR" -> "云上报链路异常";
            case "DISABLED" -> "云上报未启用";
            default -> "云上报状态未知";
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> activeHandler(Map<String, Map<String, Object>> handlers) {
        String mode = normalizedMode();
        return handlers.entrySet().stream()
                .filter(entry -> mode.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Collections.emptyMap());
    }

    /**
     * 校验业务条件和参数边界。
     */
    private boolean requiresPersistentConnection() {
        String mode = normalizedMode();
        return "MQTT".equals(mode) || "TCP".equals(mode);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasActiveTransport(Map<String, Map<String, Object>> handlersStatus,
                                       Map<String, Map<String, Object>> handlersStatistics) {
        String mode = normalizedMode();
        Map<String, Object> status = activeHandler(handlersStatus);
        Map<String, Object> statistics = activeHandler(handlersStatistics);
        if ("MQTT".equals(mode)) {
            Object clientManager = statistics.get("clientManager");
            if (clientManager instanceof Map<?, ?> manager) {
                return number(manager.get("connectedClients")) > 0;
            }
            return false;
        }
        if ("TCP".equals(mode)) {
            return number(status.get("activeConnections")) > 0;
        }
        return false;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizedMode() {
        return String.valueOf(reportProperties.getMode()).trim().toUpperCase();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> batchOptions() {
        ReportProperties.Cloud.Batch batch = reportProperties.getCloud().getBatch();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.ENABLED, batch.isEnabled());
        result.put("maxDevicesPerPack", batch.getMaxDevicesPerPack());
        result.put("maxPropertiesPerPack", batch.getMaxPropertiesPerPack());
        result.put("maxPayloadBytes", batch.getMaxPayloadBytes());
        result.put("maxDelayMs", batch.getMaxDelayMs());
        result.put("highPriorityBypass", batch.isHighPriorityBypass());
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> ackOptions() {
        ReportProperties.Cloud.Ack ack = reportProperties.getCloud().getAck();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.MODE, ack.getMode());
        result.put("timeoutMs", ack.getTimeoutMs());
        result.put("maxPending", ack.getMaxPending());
        result.put("timeoutScanMs", ack.getTimeoutScanMs());
        result.put("commitOn", ack.getCommitOn());
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> outboxMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.ENABLED, cloudOutboxService.isEnabled());
        result.put("pendingCount", cloudOutboxService.getPendingCount());
        result.put("isolatedCount", cloudOutboxService.getIsolatedCount());
        result.put("oldestMessageAgeMs", cloudOutboxService.getOldestMessageAgeMillis());
        return result;
    }
    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private List<String> safeList(List<String> source) {
        return source == null ? Collections.emptyList() : new ArrayList<>(source);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Map<String, Object>> safeMap(Map<String, Map<String, Object>> source) {
        return source == null ? Collections.emptyMap() : new LinkedHashMap<>(source);
    }

    /**
     * 执行当前业务逻辑。
     */
    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }
}
