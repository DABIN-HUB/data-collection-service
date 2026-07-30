package com.wangbin.collector.core.alarm;

/**
 * 告警事件元数据字段。
 */
public final class AlarmMetadataKeys {

    public static final String EVENT_TRIGGERED = "eventTriggered";
    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_LEVEL = "eventLevel";
    public static final String EVENT_MESSAGE = "eventMessage";
    public static final String EVENT_ID = "eventId";
    public static final String RELATED_EVENT_ID = "relatedEventId";
    public static final String ALARM_STARTED_AT = "alarmStartedAt";
    public static final String ALARM_OCCURRED_AT = "alarmOccurredAt";
    public static final String ALARM_DURATION_MILLIS = "alarmDurationMillis";

    private AlarmMetadataKeys() {
    }
}
