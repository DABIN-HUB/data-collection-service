package com.wangbin.collector.core.processor;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.AlarmRule;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.alarm.AlarmStateTracker;
import com.wangbin.collector.core.alarm.AlarmMetadataKeys;
import com.wangbin.collector.core.alarm.AlarmTransition;
import com.wangbin.collector.core.alarm.AlarmTransitionType;
import com.wangbin.collector.common.domain.alert.AlertNotification;
import com.wangbin.collector.core.port.AlertPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 校验采集数据质量，并为异常数据补充可上报的告警元数据。
 */
@Slf4j
@Component
public class DataQualityProcessor extends AbstractDataProcessor {

    private static final String QUALITY_EVENT_TYPE = "QUALITY";
    private static final String ALARM_EVENT_TYPE = "ALARM";
    private static final String ALARM_RECOVERED_EVENT_TYPE = "ALARM_RECOVERED";
    private final AlertPublisher alertPublisher;
    private final AlarmStateTracker alarmStateTracker;
    /**
     * 创建当前组件实例。
     */
    public DataQualityProcessor(AlertPublisher alertPublisher,
                                AlarmStateTracker alarmStateTracker) {
        this.alertPublisher = alertPublisher;
        this.alarmStateTracker = alarmStateTracker;
        this.name = "DataQualityProcessor";
        this.type = "QUALITY";
        this.description = "数据质量处理器";
        this.priority = 20;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doInit() throws Exception {
        log.info("DataQualityProcessor 已初始化:{}", getName());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected ProcessResult doProcess(ProcessContext context, DataPoint point, Object rawValue) throws Exception {
        if (rawValue == null) {
            return buildQualityError(point, null, "数值为空", DataQuality.BAD);
        }

        try {
            String rangeIssue = validateRange(point, rawValue);
            if (rangeIssue != null) {
                return buildQualityError(point, rawValue, rangeIssue, DataQuality.VALUE_INVALID);
            }

            AlarmEvent alarmEvent = null;
            if (point.getAlarmEnabled() != null && point.getAlarmEnabled() == 1) {
                alarmEvent = checkAlarmRules(point, rawValue, context);
            }

            ProcessResult result = ProcessResult.success(rawValue, rawValue, "质量检查通过");
            if (alarmEvent != null) {
                if (ALARM_EVENT_TYPE.equals(alarmEvent.type)) {
                    result.setQuality(QualityEnum.WARNING.getCode());
                    result.setQualityDescription(QualityEnum.WARNING.getText());
                }
                applyAlarmMetadata(result, point, alarmEvent, context, rawValue);
            }
            return result;

        } catch (Exception e) {
            log.error("质量检查错误：点位={}, 值={}", point.getPointName(), rawValue, e);
            ProcessResult error = ProcessResult.error(rawValue,
                    "质量检查异常：" + e.getMessage(), DataQuality.PROCESS_ERROR);
            applyAlarmMetadata(error, point,
                    new AlarmEvent(QUALITY_EVENT_TYPE, "ERROR", "质量检查异常", null, null), context, rawValue);
            return error;
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildQualityError(DataPoint point, Object rawValue, String message, DataQuality quality) {
        ProcessResult result = ProcessResult.error(rawValue, message, quality);
        applyAlarmMetadata(result, point,
                new AlarmEvent(QUALITY_EVENT_TYPE, quality.getDescription(), message, null, null), null, rawValue);
        return result;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private String validateRange(DataPoint point, Object value) {
        if (point == null || (point.getMinValue() == null && point.getMaxValue() == null)) {
            return null;
        }

        double doubleValue = convertToDouble(value);
        if (Double.isNaN(doubleValue)) {
            return "数值无法解析";
        }

        if (point.getMinValue() != null && doubleValue < point.getMinValue()) {
            log.warn("值 低于最小值:点位={}, 值={}, 最小值={}",
                    point.getPointName(), doubleValue, point.getMinValue());
            return "数值低于配置最小值";
        }

        if (point.getMaxValue() != null && doubleValue > point.getMaxValue()) {
            log.warn("值 高于最大值:点位={}, 值={}, 最大值={}",
                    point.getPointName(), doubleValue, point.getMaxValue());
            return "数值高于配置最大值";
        }

        return null;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private AlarmEvent checkAlarmRules(DataPoint point, Object value, ProcessContext context) {
        List<AlarmRule> rules = point.getAlarmRule();
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        double doubleValue = convertToDouble(value);
        if (Double.isNaN(doubleValue)) {
            return null;
        }

        for (AlarmRule rule : rules) {
            if (rule == null || (rule.getEnabled() != null && !rule.getEnabled())) {
                continue;
            }

            long processTime = context != null && context.getProcessTime() > 0
                    ? context.getProcessTime() : System.currentTimeMillis();
            AlarmTransition transition = alarmStateTracker.evaluate(
                    point.getDeviceId(), point.getPointId(), rule, doubleValue, processTime);
            if (transition.type() == AlarmTransitionType.ACTIVATED) {
                String level = rule.getLevel() != null ? rule.getLevel() : "WARNING";
                String message = rule.getDescription() != null ? rule.getDescription() : "触发告警";
                log.warn("触发告警：设备={}, 点位={}, 规则={}, 值={}",
                        point.getDeviceId(), point.getPointName(), rule.getRuleName(), doubleValue);
                return new AlarmEvent(ALARM_EVENT_TYPE, level, message,
                        rule.getRuleId(), rule.getRuleName(), transition.alarmId(), null,
                        transition.startedAt(), transition.occurredAt(), transition.durationMillis());
            }
            if (transition.type() == AlarmTransitionType.RECOVERED) {
                String message = rule.getDescription() != null
                        ? rule.getDescription() + "，告警已恢复" : "告警已恢复";
                String recoveryEventId = transition.alarmId() + ":recovered";
                return new AlarmEvent(ALARM_RECOVERED_EVENT_TYPE, "INFO",
                        message, rule.getRuleId(), rule.getRuleName(), recoveryEventId,
                        transition.alarmId(), transition.startedAt(),
                        transition.occurredAt(), transition.durationMillis());
            }
        }
        return null;
    }

    /**
     * 处理当前业务流程。
     */
    private void applyAlarmMetadata(ProcessResult result,
                                    DataPoint point,
                                    AlarmEvent event,
                                    ProcessContext context,
                                    Object rawValue) {
        if (result == null || event == null) {
            return;
        }
        result.addMetadata(AlarmMetadataKeys.EVENT_TRIGGERED, true);
        result.addMetadata(AlarmMetadataKeys.EVENT_TYPE, event.type);
        result.addMetadata(AlarmMetadataKeys.EVENT_LEVEL, event.level);
        result.addMetadata(AlarmMetadataKeys.EVENT_MESSAGE, event.message);
        if (event.eventId != null) {
            result.addMetadata(AlarmMetadataKeys.EVENT_ID, event.eventId);
        }
        if (event.startedAt > 0L) {
            result.addMetadata(AlarmMetadataKeys.ALARM_STARTED_AT, event.startedAt);
            result.addMetadata(AlarmMetadataKeys.ALARM_OCCURRED_AT, event.occurredAt);
            result.addMetadata(AlarmMetadataKeys.ALARM_DURATION_MILLIS, event.durationMillis);
        }
        if (event.relatedEventId != null) {
            result.addMetadata(AlarmMetadataKeys.RELATED_EVENT_ID, event.relatedEventId);
        }
        if (event.ruleId != null) {
            result.addMetadata(CommonMapKeys.RULE_ID, event.ruleId);
        }
        if (event.ruleName != null) {
            result.addMetadata(CommonMapKeys.RULE_NAME, event.ruleName);
        }
        if (point != null) {
            if (point.getPointCode() != null) {
                result.addMetadata(CommonMapKeys.POINT_CODE, point.getPointCode());
            }
            if (point.getDeviceId() != null) {
                result.addMetadata(CommonMapKeys.RAW_DEVICE_ID, point.getDeviceId());
            }
        }
        notifyAlert(point, event, context, result, rawValue);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void notifyAlert(DataPoint point,
                             AlarmEvent event,
                             ProcessContext context,
                             ProcessResult result,
                             Object rawValue) {
        if (alertPublisher == null || event == null) {
            return;
        }
        String contextDeviceId = null;
        if (context != null) {
            contextDeviceId = context.getAttribute("deviceId", null);
            if (contextDeviceId == null && context.getDeviceInfo() != null) {
                contextDeviceId = context.getDeviceInfo().getDeviceId();
            }
        }
        AlertNotification notification =
                AlertNotification.builder()
                        .deviceId(point != null && point.getDeviceId() != null
                                ? point.getDeviceId()
                                : contextDeviceId)
                        .deviceName(point != null ? point.getDeviceName() : null)
                        .pointId(point != null ? point.getPointId() : null)
                        .pointCode(point != null ? point.getPointCode() : null)
                        .ruleId(event.ruleId)
                        .ruleName(event.ruleName)
                        .level(event.level)
                        .eventType(event.type)
                        .eventId(event.eventId)
                        .relatedEventId(event.relatedEventId)
                        .startedAt(event.startedAt)
                        .durationMillis(event.durationMillis)
                        .message(event.message)
                        .value(result != null ? result.getFinalValue(rawValue) : rawValue)
                        .unit(point != null ? point.getUnit() : null)
                        .timestamp(context != null ? context.getProcessTime() : System.currentTimeMillis())
                        .build();
        alertPublisher.notifyAlert(notification, false);
    }

    /**
     * 解析或转换业务数据。
     */
    private double convertToDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }

        try {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof String) {
                return Double.parseDouble((String) value);
            }
            if (value instanceof Boolean) {
                return (Boolean) value ? 1.0 : 0.0;
            }
        } catch (Exception ignored) {
            return Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDestroy() throws Exception {
        log.info("DataQualityProcessor 已销毁:{}", getName());
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static final class AlarmEvent {
        private final String type;
        private final String level;
        private final String message;
        private final String ruleId;
        private final String ruleName;
        private final String eventId;
        private final String relatedEventId;
        private final long startedAt;
        private final long occurredAt;
        private final long durationMillis;

        /**
         * 创建当前组件实例。
         */
        private AlarmEvent(String type,
                           String level,
                           String message,
                           String ruleId,
                           String ruleName) {
            this(type, level, message, ruleId, ruleName,
                    null, null, 0L, 0L, 0L);
        }

        /**
         * 创建当前组件实例。
         */
        private AlarmEvent(String type,
                           String level,
                           String message,
                           String ruleId,
                           String ruleName,
                           String eventId,
                           String relatedEventId,
                           long startedAt,
                           long occurredAt,
                           long durationMillis) {
            this.type = type;
            this.level = level;
            this.message = message;
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.eventId = eventId;
            this.relatedEventId = relatedEventId;
            this.startedAt = startedAt;
            this.occurredAt = occurredAt;
            this.durationMillis = durationMillis;
        }
    }
}
