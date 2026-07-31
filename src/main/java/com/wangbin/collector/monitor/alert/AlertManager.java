package com.wangbin.collector.monitor.alert;

import com.wangbin.collector.core.report.service.CacheReportService;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 管理当前模块的生命周期和状态。
 */
@Slf4j
@Component
public class AlertManager {

    private final CacheReportService cacheReportService;

    @Autowired(required = false)
    private AlarmHistoryService alarmHistoryService;

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final Queue<AlertNotification> recentAlerts = new ConcurrentLinkedQueue<>();
    private final int maxHistorySize = 1000;

    /**
     * 创建当前组件实例。
     */
    public AlertManager(CacheReportService cacheReportService) {
        this.cacheReportService = cacheReportService;
    }

    /**
     * 维护注册或订阅关系。
     */
    public void register(AlertRule rule) {
        rules.put(rule.getId(), rule);
        log.info("已注册 告警 规则 {}", rule.getName());
    }

    public Collection<AlertRule> getRules() {
        return rules.values();
    }

    /**
     * 清理或删除业务数据。
     */
    public void remove(String ruleId) {
        rules.remove(ruleId);
    }

    /**
     * 执行当前业务逻辑。
     */
    public void notifyAlert(AlertNotification notification) {
        notifyAlert(notification, true);
    }

    /**
     * 记录告警，并按调用来源决定是否直接上传云端。
     *
     * @param notification 告警通知
     * @param uploadToCloud 是否直接上传云端
     */
    public void notifyAlert(AlertNotification notification, boolean uploadToCloud) {
        if (notification == null) {
            return;
        }
        recentAlerts.add(notification);
        while (recentAlerts.size() > maxHistorySize) {
            recentAlerts.poll();
        }
        log.warn("告警 triggered:设备={}, 点位={}, 级别={}, 消息={}",
                notification.getDeviceId(),
                notification.getPointCode() != null ? notification.getPointCode() : notification.getPointId(),
                notification.getLevel(),
                notification.getMessage());
        saveAlarmHistory(notification);
        if (uploadToCloud) {
            cacheReportService.reportAlert(notification);
        }
    }

    public List<AlertNotification> getRecentAlerts() {
        return List.copyOf(recentAlerts);
    }

    /**
     * 评估指标类规则，并在触发时推送告警通知。
     */
    public void evaluate(String metric, double value) {
        for (AlertRule rule : rules.values()) {
            boolean triggered = rule.getConditions().stream()
                    .allMatch(condition -> compare(condition, metric, value));
            if (triggered) {
                notifyAlert(AlertNotification.builder()
                        .eventType("METRIC")
                        .ruleId(rule.getId())
                        .ruleName(rule.getName())
                        .level(rule.getLevel() != null ? rule.getLevel().name() : "WARNING")
                        .message("Metric " + metric + " threshold reached")
                        .value(value)
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void saveAlarmHistory(AlertNotification notification) {
        if (alarmHistoryService == null) {
            return;
        }
        try {
            alarmHistoryService.saveAsync(notification);
        } catch (Exception e) {
            log.error("提交 告警 历史 写入 失败, 设备={}, 点位={}",
                    notification.getDeviceId(), notification.getPointId(), e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean compare(AlertCondition condition, String metric, double value) {
        if (!condition.getMetric().equals(metric)) {
            return true;
        }
        return switch (condition.getComparator()) {
            case GREATER_THAN -> value > condition.getThreshold();
            case LESS_THAN -> value < condition.getThreshold();
            case EQUALS -> value == condition.getThreshold();
        };
    }
}
