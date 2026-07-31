package com.wangbin.collector.core.alarm;

import com.wangbin.collector.common.domain.entity.AlarmRule;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
    public AlarmStateTracker() {
        this(new InMemoryAlarmStateRepository());
    }

    /**
     * 创建当前组件实例。
     */
    @Autowired
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

    /**
     * 执行当前业务逻辑。
     */
    private RuleState restoreState(String stateKey) {
        return stateRepository.find(stateKey)
                .map(snapshot -> new RuleState(
                        snapshot.getLifecycleState(),
                        snapshot.getPendingSince(),
                        snapshot.getActiveSince(),
                        snapshot.getAlarmId()))
                .orElseGet(RuleState::new);
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistState(String stateKey, RuleState state, long updatedAt) {
        stateRepository.save(new AlarmStateSnapshot(
                stateKey,
                state.lifecycleState,
                state.pendingSince,
                state.activeSince,
                state.alarmId,
                updatedAt));
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
                return AlarmTransition.recovered(
                        state.alarmId, state.activeSince, timestamp);
            }
            return AlarmTransition.none(state.lifecycleState);
        }

        if (state.lifecycleState == AlarmLifecycleState.RECOVERED) {
            state.lifecycleState = AlarmLifecycleState.NORMAL;
            state.alarmId = null;
            state.activeSince = 0L;
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
                          String alarmId) {
            this.lifecycleState = lifecycleState == null
                    ? AlarmLifecycleState.NORMAL : lifecycleState;
            this.pendingSince = pendingSince;
            this.activeSince = activeSince;
            this.alarmId = alarmId;
        }
    }
}
