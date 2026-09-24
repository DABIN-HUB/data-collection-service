package com.wangbin.collector.core.alarm;

import com.wangbin.collector.common.domain.entity.AlarmRule;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按设备、点位和规则维护告警状态。
 */
@Component
public class AlarmStateTracker {

    private static final long MILLIS_PER_SECOND = 1_000L;
    private final ConcurrentMap<String, RuleState> states = new ConcurrentHashMap<>();
    private final AlarmStateRepository stateRepository;


    /**
     * 创建当前组件实例。
     */
    public AlarmStateTracker(AlarmStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    /**
     * 执行当前业务逻辑。
     */
    public AlarmTransition evaluate(String deviceId,
                                    String pointId,
                                    AlarmRule rule,
                                    double value,
                                    long timestamp) {
        if (rule == null) {
            return AlarmTransition.none(AlarmLifecycleState.NORMAL);
        }
        String stateKey = stateKey(deviceId, pointId, rule);
        RuleState ruleState = states.computeIfAbsent(stateKey, this::restoreState);
        synchronized (ruleState) {
            ruleState.deviceId = deviceId;
            ruleState.pointId = pointId;
            ruleState.ruleId = rule.getRuleId();
            ruleState.ruleName = rule.getRuleName();
            AlarmTransition transition = evaluateState(stateKey, ruleState, rule, value, timestamp);
            persistState(stateKey, ruleState, timestamp);
            return transition;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean acknowledge(String deviceId, String pointId, AlarmRule rule) {
        String stateKey = stateKey(deviceId, pointId, rule);
        RuleState state = states.computeIfAbsent(stateKey, this::restoreState);
        synchronized (state) {
            if (state.lifecycleState != AlarmLifecycleState.ACTIVE) {
                return false;
            }
            state.lifecycleState = AlarmLifecycleState.ACKED;
            persistState(stateKey, state, System.currentTimeMillis());
            return true;
        }
    }

    /** 按事件标识确认运行态；历史事件不能确认同一规则的新事件。 */
    public boolean acknowledgeByAlarmId(String alarmId, String operator, long ackedAt,
                                        String note, String idempotencyKey) {
        if (!StringUtils.hasText(alarmId)) {
            return false;
        }
        for (Map.Entry<String, RuleState> entry : states.entrySet()) {
            RuleState current = entry.getValue();
            synchronized (current) {
                if (alarmId.equals(current.alarmId)) {
                    return acknowledgeCurrent(entry.getKey(), current, alarmId, operator, ackedAt, note, idempotencyKey);
                }
            }
        }
        AlarmStateSnapshot snapshot = stateRepository.findByAlarmId(alarmId).orElse(null);
        if (snapshot == null) return false;
        String stateKey = snapshot.getStateKey();
        RuleState state = states.computeIfAbsent(stateKey, this::restoreState);
        synchronized (state) {
            return acknowledgeCurrent(stateKey, state, alarmId, operator, ackedAt, note, idempotencyKey);
        }
    }

    private boolean acknowledgeCurrent(String stateKey, RuleState state, String alarmId,
                                       String operator, long ackedAt, String note, String idempotencyKey) {
            if (!alarmId.equals(state.alarmId)
                    || stateRepository.find(stateKey)
                    .map(snapshot -> !alarmId.equals(snapshot.getAlarmId()))
                    .orElse(false)) return false;
            if (state.lifecycleState != AlarmLifecycleState.ACTIVE
                    && state.lifecycleState != AlarmLifecycleState.ACKED
                    && state.lifecycleState != AlarmLifecycleState.RECOVERED) {
                return false;
            }
            if (state.lifecycleState == AlarmLifecycleState.ACTIVE) {
                state.lifecycleState = AlarmLifecycleState.ACKED;
            }
            if (state.ackedAt == 0L) {
                state.ackedBy = operator;
                state.ackedAt = ackedAt;
                state.ackNote = note;
                state.ackIdempotencyKey = idempotencyKey;
            }
            persistState(stateKey, state, ackedAt);
            return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    private RuleState restoreState(String stateKey) {
        return stateRepository.find(stateKey)
                .map(snapshot -> new RuleState(
                        snapshot.getLifecycleState(),
                        snapshot.getPendingSince(),
                        snapshot.getActiveSince(),
                        snapshot.getAlarmId(), snapshot.getLastOccurredAt(),
                        snapshot.getAckedBy(), snapshot.getAckedAt(),
                        snapshot.getAckNote(), snapshot.getAckIdempotencyKey(),
                        snapshot.getRecoveredAt(), snapshot.getDeviceId(), snapshot.getPointId(),
                        snapshot.getRuleId(), snapshot.getRuleName()))
                .orElseGet(RuleState::new);
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistState(String stateKey, RuleState state, long updatedAt) {
        AlarmStateSnapshot snapshot = new AlarmStateSnapshot(
                stateKey,
                state.lifecycleState,
                state.pendingSince,
                state.activeSince,
                state.alarmId,
                updatedAt);
        snapshot.setLastOccurredAt(state.lastOccurredAt);
        snapshot.setAckedBy(state.ackedBy);
        snapshot.setAckedAt(state.ackedAt);
        snapshot.setAckNote(state.ackNote);
        snapshot.setAckIdempotencyKey(state.ackIdempotencyKey);
        snapshot.setRecoveredAt(state.recoveredAt);
        snapshot.setDeviceId(state.deviceId);
        snapshot.setPointId(state.pointId);
        snapshot.setRuleId(state.ruleId);
        snapshot.setRuleName(state.ruleName);
        stateRepository.save(snapshot);
    }

    /**
     * 执行当前业务逻辑。
     */
    private AlarmTransition evaluateState(String stateKey,
                                          RuleState state,
                                          AlarmRule rule,
                                          double value,
                                          long timestamp) {
        boolean matched = rule.checkAlarm(value);
        if (state.lifecycleState == AlarmLifecycleState.ACTIVE
                || state.lifecycleState == AlarmLifecycleState.ACKED) {
            if (isRecovered(rule, value)) {
                state.lifecycleState = AlarmLifecycleState.RECOVERED;
                state.pendingSince = 0L;
                state.recoveredAt = timestamp;
                return AlarmTransition.recovered(
                        state.alarmId, state.activeSince, timestamp, state.lastOccurredAt);
            }
            if (matched) {
                state.lastOccurredAt = Math.max(state.lastOccurredAt, timestamp);
            }
            return AlarmTransition.none(state.lifecycleState);
        }

        if (state.lifecycleState == AlarmLifecycleState.RECOVERED) {
            if (!matched) {
                return AlarmTransition.none(state.lifecycleState);
            }
        }
        if (!matched) {
            state.lifecycleState = AlarmLifecycleState.NORMAL;
            state.pendingSince = 0L;
            return AlarmTransition.none(state.lifecycleState);
        }

        long durationMillis = Math.max(0L,
                rule.getDuration() == null ? 0L : rule.getDuration() * MILLIS_PER_SECOND);
        if (durationMillis == 0L) {
            return activate(stateKey, state, timestamp, timestamp);
        }
        if (state.lifecycleState != AlarmLifecycleState.PENDING) {
            state.lifecycleState = AlarmLifecycleState.PENDING;
            state.pendingSince = timestamp;
            return AlarmTransition.none(state.lifecycleState);
        }
        if (timestamp - state.pendingSince >= durationMillis) {
            return activate(stateKey, state, state.pendingSince, timestamp);
        }
        return AlarmTransition.none(state.lifecycleState);
    }

    /**
     * 执行当前业务逻辑。
     */
    private AlarmTransition activate(String stateKey,
                                     RuleState state,
                                     long startedAt,
                                     long occurredAt) {
        state.lifecycleState = AlarmLifecycleState.ACTIVE;
        state.activeSince = startedAt;
        state.lastOccurredAt = occurredAt;
        state.recoveredAt = 0L;
        state.ackedBy = null;
        state.ackedAt = 0L;
        state.ackNote = null;
        state.ackIdempotencyKey = null;
        state.alarmId = UUID.nameUUIDFromBytes(
                (stateKey + "|" + startedAt).getBytes(StandardCharsets.UTF_8)).toString();
        return AlarmTransition.activated(state.alarmId, startedAt, occurredAt);
    }

    private boolean isRecovered(AlarmRule rule, double value) {
        if (rule.getThreshold() == null || !StringUtils.hasText(rule.getOperator())) {
            return true;
        }
        double threshold = rule.getThreshold();
        double hysteresis = resolveHysteresis(rule.getAdditionalConfig());
        return switch (rule.getOperator()) {
            case ">", ">=" -> value <= threshold - hysteresis;
            case "<", "<=" -> value >= threshold + hysteresis;
            case "==" -> !Double.valueOf(value).equals(rule.getThreshold());
            case "!=" -> Double.valueOf(value).equals(rule.getThreshold());
            default -> !rule.checkAlarm(value);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private double resolveHysteresis(Map<String, Object> additionalConfig) {
        if (additionalConfig == null) {
            return 0D;
        }
        Object configured = additionalConfig.get(AlarmConfigKeys.HYSTERESIS);
        if (configured instanceof Number number) {
            return Math.max(0D, number.doubleValue());
        }
        if (configured instanceof String text) {
            try {
                return Math.max(0D, Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return 0D;
            }
        }
        return 0D;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String stateKey(String deviceId, String pointId, AlarmRule rule) {
        String ruleKey = StringUtils.hasText(rule.getRuleId())
                ? rule.getRuleId()
                : rule.getOperator() + ":" + rule.getThreshold();
        return String.valueOf(deviceId) + "|" + pointId + "|" + ruleKey;
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static final class RuleState {
        private AlarmLifecycleState lifecycleState = AlarmLifecycleState.NORMAL;
        private long pendingSince;
        private long activeSince;
        private String alarmId;
        private long lastOccurredAt;
        private String ackedBy;
        private long ackedAt;
        private String ackNote;
        private String ackIdempotencyKey;
        private long recoveredAt;
        private String deviceId;
        private String pointId;
        private String ruleId;
        private String ruleName;

        /**
         * 创建当前组件实例。
         */
        private RuleState() {
        }

        /**
         * 创建当前组件实例。
         */
        private RuleState(AlarmLifecycleState lifecycleState,
                          long pendingSince,
                          long activeSince,
                          String alarmId, long lastOccurredAt, String ackedBy,
                          long ackedAt, String ackNote, String ackIdempotencyKey,
                          long recoveredAt, String deviceId, String pointId, String ruleId, String ruleName) {
            this.lifecycleState = lifecycleState == null
                    ? AlarmLifecycleState.NORMAL : lifecycleState;
            this.pendingSince = pendingSince;
            this.activeSince = activeSince;
            this.alarmId = alarmId;
            this.lastOccurredAt = lastOccurredAt == 0L && activeSince > 0L
                    ? activeSince : lastOccurredAt;
            this.ackedBy = ackedBy;
            this.ackedAt = ackedAt;
            this.ackNote = ackNote;
            this.ackIdempotencyKey = ackIdempotencyKey;
            this.recoveredAt = recoveredAt;
            this.deviceId = deviceId;
            this.pointId = pointId;
            this.ruleId = ruleId;
            this.ruleName = ruleName;
        }
    }
}
