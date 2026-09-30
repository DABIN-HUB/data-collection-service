package com.wangbin.collector.core.alarm;

/**
 * 告警状态转换结果
 *
 * @param type 转换类型
 * @param state 当前状态
 * @param alarmId 原始告警标识
 * @param startedAt 告警开始时间
 * @param occurredAt 本次转换时间
 * @param durationMillis 告警持续时间
 * @param lastOccurredAt 本次事件最后一次命中的时间
 */
public record AlarmTransition(AlarmTransitionType type,
                              AlarmLifecycleState state,
                              String alarmId,
                              long startedAt,
                              long occurredAt,
                              long durationMillis,
                              long lastOccurredAt) {

    /**
     * 执行当前业务逻辑。
     */
    public static AlarmTransition none(AlarmLifecycleState state) {
        return new AlarmTransition(AlarmTransitionType.NONE, state, null, 0L, 0L, 0L, 0L);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static AlarmTransition activated(String alarmId, long startedAt, long occurredAt) {
        return new AlarmTransition(AlarmTransitionType.ACTIVATED, AlarmLifecycleState.ACTIVE,
                alarmId, startedAt, occurredAt, 0L, occurredAt);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static AlarmTransition recovered(String alarmId,
                                            long startedAt,
                                            long occurredAt,
                                            long lastOccurredAt) {
        return new AlarmTransition(AlarmTransitionType.RECOVERED, AlarmLifecycleState.RECOVERED,
                alarmId, startedAt, occurredAt, Math.max(0L, occurredAt - startedAt), lastOccurredAt);
    }

    public static AlarmTransition recovered(String alarmId, long startedAt, long occurredAt) {
        return recovered(alarmId, startedAt, occurredAt, occurredAt);
    }
}
