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

@Slf4j
@Component
public class AlertManager {

    private final CacheReportService cacheReportService;

    @Autowired(required = false)
    private AlarmHistoryService alarmHistoryService;

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final Queue<AlertNotification> recentAlerts = new ConcurrentLinkedQueue<>();
    private final int maxHistorySize = 1000;

    public AlertManager(CacheReportService cacheReportService) {
        this.cacheReportService = cacheReportService;
    }

    public void register(AlertRule rule) {
        rules.put(rule.getId(), rule);
        log.info("Registered alert rule {}", rule.getName());
    }

    public Collection<AlertRule> getRules() {
        return rules.values();
    }

    public void remove(String ruleId) {
        rules.remove(ruleId);
    }

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
        log.warn("Alert triggered: device={}, point={}, level={}, message={}",
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
     * Evaluate metric-based rules and push notifications when triggered.
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

    private void saveAlarmHistory(AlertNotification notification) {
        if (alarmHistoryService == null) {
            return;
        }
        try {
            alarmHistoryService.saveAsync(notification);
        } catch (Exception e) {
            log.error("submit alarm history write failed, device={}, point={}",
                    notification.getDeviceId(), notification.getPointId(), e);
        }
    }

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
