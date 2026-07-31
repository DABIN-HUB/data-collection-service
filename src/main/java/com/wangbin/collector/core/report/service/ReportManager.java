package com.wangbin.collector.core.report.service;

import com.wangbin.collector.common.constant.ProtocolConstant;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.handler.ReportHandler;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 上报管理器：根据协议调度具体的上报处理器。
 */
@Slf4j
@Service
public class ReportManager {

    private final Map<String, ReportHandler> handlers = new ConcurrentHashMap<>();
    private final Executor reportExecutor;
    private final ReportProperties reportProperties;
    private final List<ReportHandler> handlerCandidates;

    /**
     * 创建当前组件实例。
     */
    @Autowired
    public ReportManager(ReportProperties reportProperties,
                         @Qualifier("reportExecutor") Executor reportExecutor,
                         List<ReportHandler> handlerCandidates) {
        this.reportProperties = reportProperties;
        this.reportExecutor = reportExecutor;
        this.handlerCandidates = handlerCandidates;
    }

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void init() {
        log.info("ReportManager 初始化...");
        registerHandlers();
        log.info("已启用的上报处理器: {}", handlers.keySet());
    }

    /**
     * 维护注册或订阅关系。
     */
    private void registerHandlers() {
        if (!reportProperties.isEnabled()) {
            log.warn("collector.report.enabled=false，已关闭上报功能");
            return;
        }
        for (ReportHandler candidate : handlerCandidates) {
            if (candidate == null) {
                continue;
            }
            String protocol = candidate.getProtocol();
            if (!reportProperties.isProtocolEnabled(protocol)) {
                log.info("跳过协议 {}，未在配置中启用", protocol);
                continue;
            }
            try {
                candidate.init();
                handlers.put(protocol.toUpperCase(Locale.ROOT), candidate);
                log.info("注册上报处理器: {}", protocol);
            } catch (Exception e) {
                log.error("初始化上报处理器 [{}] 失败", protocol, e);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public CompletableFuture<ReportResult> reportAsync(ReportData data, ReportConfig config) {
        if (!reportProperties.isEnabled()) {
            return CompletableFuture.completedFuture(disabledResult(data, config));
        }
        CompletableFuture<ReportResult> future = new CompletableFuture<>();
        try {
            reportExecutor.execute(() -> {
                try {
                    future.complete(report(data, config));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("上报 执行器 被拒绝 任务, 目标ID={}, 点位编码={}",
                    config != null ? config.getTargetId() : "unknown",
                    data != null ? data.getPointCode() : "unknown",
                    e);
            ReportResult rejected = ReportResult.error(
                    data != null ? data.getPointCode() : "unknown",
                    "report executor overloaded: " + e.getMessage(),
                    config != null ? config.getTargetId() : "unknown"
            );
            rejected.addMetadata("rejected", true);
            rejected.addMetadata("retryable", true);
            rejected.addMetadata("reason", "executor_rejected");
            future.complete(rejected);
        }
        return future;
    }

    /**
     * 执行当前业务逻辑。
     */
    public ReportResult report(ReportData data, ReportConfig config) {
        if (!reportProperties.isEnabled()) {
            return disabledResult(data, config);
        }
        if (data == null || config == null) {
            return ReportResult.error(
                    data != null ? data.getPointCode() : "unknown",
                    "上报参数缺失",
                    config != null ? config.getTargetId() : "unknown"
            );
        }

        if (!config.validate()) {
            return ReportResult.error(data.getPointCode(),
                    "上报配置校验失败", config.getTargetId());
        }

        try {
            ReportHandler handler = getHandler(config.getProtocol());
            if (handler == null) {
                return ReportResult.error(data.getPointCode(),
                        "未找到协议处理器: " + config.getProtocol(), config.getTargetId());
            }

            return handler.report(data, config);

        } catch (Exception e) {
            log.error("上报失败: {} -> {}", data.getPointCode(), config.getTargetId(), e);
            return ReportResult.error(data.getPointCode(),
                    "上报异常: " + e.getMessage(), config.getTargetId());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public List<ReportResult> batchReport(List<ReportData> dataList, ReportConfig config) {
        if (!reportProperties.isEnabled()) {
            return Collections.emptyList();
        }
        if (dataList == null || dataList.isEmpty() || config == null) {
            return Collections.emptyList();
        }

        if (!config.validate()) {
            List<ReportResult> errors = new ArrayList<>();
            for (ReportData data : dataList) {
                errors.add(ReportResult.error(data.getPointCode(),
                        "上报配置校验失败", config.getTargetId()));
            }
            return errors;
        }

        try {
            ReportHandler handler = getHandler(config.getProtocol());
            if (handler == null) {
                List<ReportResult> errors = new ArrayList<>();
                for (ReportData data : dataList) {
                    errors.add(ReportResult.error(data.getPointCode(),
                            "未找到协议处理器: " + config.getProtocol(), config.getTargetId()));
                }
                return errors;
            }

            return handler.batchReport(dataList, config);

        } catch (Exception e) {
            log.error("批量上报失败: {}", config.getTargetId(), e);
            List<ReportResult> errors = new ArrayList<>();
            for (ReportData data : dataList) {
                errors.add(ReportResult.error(data.getPointCode(),
                        "批量上报异常: " + e.getMessage(), config.getTargetId()));
            }
            return errors;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public List<ReportResult> reportWithMode(List<ReportData> dataList, ReportConfig config, String reportMode) {
        if (!reportProperties.isEnabled()) {
            return Collections.emptyList();
        }
        String normalizedMode = reportMode == null
                ? ProtocolConstant.REPORT_MODE_REALTIME
                : reportMode.toUpperCase(Locale.ROOT);

        return switch (normalizedMode) {
            case ProtocolConstant.REPORT_MODE_REALTIME ->
                    dataList.stream().map(data -> report(data, config)).toList();
            case ProtocolConstant.REPORT_MODE_BATCH -> batchReport(dataList, config);
            case ProtocolConstant.REPORT_MODE_COMPRESSED -> reportCompressed(dataList, config);
            default -> batchReport(dataList, config);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<ReportResult> reportCompressed(List<ReportData> dataList, ReportConfig config) {
        log.debug("压缩模式暂未实现差异化处理，按批量上报执行，数据量: {}", dataList.size());
        return batchReport(dataList, config);
    }

    private ReportHandler getHandler(String protocol) {
        if (protocol == null) {
            return null;
        }
        return handlers.get(protocol.toUpperCase(Locale.ROOT));
    }

    public List<String> getSupportedProtocols() {
        return new ArrayList<>(handlers.keySet());
    }

    /**
     * 更新或刷新业务状态。
     */
    public void updateConfig(ReportConfig config) {
        if (config == null || !config.validate()) {
            log.warn("上报配置无效，忽略更新");
            return;
        }

        try {
            ReportHandler handler = getHandler(config.getProtocol());
            if (handler != null) {
                handler.onConfigUpdate(config);
                log.info("已更新上报配置: {}", config.getTargetId());
            } else {
                log.warn("未找到对应协议处理器: {}", config.getProtocol());
            }
        } catch (Exception e) {
            log.error("更新上报配置失败: {}", config.getTargetId(), e);
        }
    }

    /**
     * 清理或删除业务数据。
     */
    public void removeConfig(ReportConfig config) {
        if (config == null) {
            return;
        }

        try {
            ReportHandler handler = getHandler(config.getProtocol());
            if (handler != null) {
                handler.onConfigRemove(config);
                log.info("已移除上报配置: {}", config.getTargetId());
            }
        } catch (Exception e) {
            log.error("移除上报配置失败: {}", config.getTargetId(), e);
        }
    }

    public Map<String, Map<String, Object>> getHandlersStatus() {
        Map<String, Map<String, Object>> status = new HashMap<>();
        for (Map.Entry<String, ReportHandler> entry : handlers.entrySet()) {
            status.put(entry.getKey(), entry.getValue().getStatus());
        }
        return status;
    }

    public Map<String, Map<String, Object>> getHandlersStatistics() {
        Map<String, Map<String, Object>> stats = new HashMap<>();
        for (Map.Entry<String, ReportHandler> entry : handlers.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getStatistics());
        }
        return stats;
    }

    /**
     * 记录或统计业务状态。
     */
    public void resetAllStatistics() {
        for (ReportHandler handler : handlers.values()) {
            try {
                handler.resetStatistics();
            } catch (Exception e) {
                log.error("重置处理器统计失败: {}", handler.getName(), e);
            }
        }
        log.info("已重置全部上报统计");
    }

    /**
     * 处理组件生命周期。
     */
    @PreDestroy
    public void destroy() {
        log.info("ReportManager 销毁中...");

        for (ReportHandler handler : handlers.values()) {
            try {
                handler.destroy();
            } catch (Exception e) {
                log.error("销毁处理器失败: {}", handler.getName(), e);
            }
        }
        handlers.clear();

        log.info("ReportManager 已销毁");
    }

    /**
     * 执行当前业务逻辑。
     */
    private ReportResult disabledResult(ReportData data, ReportConfig config) {
        String pointCode = data != null ? data.getPointCode() : "unknown";
        String targetId = config != null ? config.getTargetId() : "unknown";
        return ReportResult.error(pointCode, "上报功能未开启", targetId);
    }
}
