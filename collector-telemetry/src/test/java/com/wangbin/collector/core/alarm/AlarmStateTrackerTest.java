package com.wangbin.collector.core.alarm;

import com.wangbin.collector.common.domain.entity.AlarmRule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AlarmStateTrackerTest {

    @Test
    void shouldActivateOnlyAfterConfiguredDurationAndRecoverWithHysteresis() {
        AlarmRule rule = new AlarmRule();
        rule.setRuleId("high-temperature");
        rule.setOperator(">");
        rule.setThreshold(80D);
        rule.setDuration(2);
        rule.setAdditionalConfig(Map.of(AlarmConfigKeys.HYSTERESIS, 5D));
        AlarmStateTracker tracker = new AlarmStateTracker(new InMemoryAlarmStateRepository());

        assertEquals(AlarmTransitionType.NONE,
                tracker.evaluate("device-1", "temperature", rule, 90D, 1_000L).type());
        assertEquals(AlarmTransitionType.NONE,
                tracker.evaluate("device-1", "temperature", rule, 90D, 2_999L).type());
        AlarmTransition activated = tracker.evaluate(
                "device-1", "temperature", rule, 90D, 3_000L);
        assertEquals(AlarmTransitionType.ACTIVATED, activated.type());
        assertNotNull(activated.alarmId());
        assertTrue(tracker.acknowledge("device-1", "temperature", rule));
        assertEquals(AlarmLifecycleState.ACKED,
                tracker.evaluate("device-1", "temperature", rule, 79D, 4_000L).state());

        AlarmTransition recovered = tracker.evaluate(
                "device-1", "temperature", rule, 75D, 5_000L);
        assertEquals(AlarmTransitionType.RECOVERED, recovered.type());
        assertEquals(activated.alarmId(), recovered.alarmId());
        assertEquals(4_000L, recovered.durationMillis());
    }

    @Test
    void shouldAcknowledgeIncidentByIdAndRetainMetadataThroughRecovery() {
        AlarmRule rule = new AlarmRule();
        rule.setRuleId("temperature");
        rule.setOperator(">");
        rule.setThreshold(10D);
        rule.setDuration(0);
        InMemoryAlarmStateRepository repository = new InMemoryAlarmStateRepository();
        AlarmStateTracker tracker = new AlarmStateTracker(repository);
        AlarmTransition activated = tracker.evaluate("device", "point", rule, 12D, 1_000L);
        tracker.evaluate("device", "point", rule, 13D, 2_000L);
        assertTrue(new AlarmStateTracker(repository).acknowledgeByAlarmId(
                activated.alarmId(), "operator", 2_500L, "handled", "request-1"));
        AlarmStateSnapshot acked = repository.findByAlarmId(activated.alarmId()).orElseThrow();
        assertEquals(AlarmLifecycleState.ACKED, acked.getLifecycleState());
        assertEquals(2_000L, acked.getLastOccurredAt());
        assertEquals("operator", acked.getAckedBy());
        assertEquals(2_500L, acked.getAckedAt());
        assertEquals("handled", acked.getAckNote());
        assertEquals("request-1", acked.getAckIdempotencyKey());
        AlarmStateTracker restarted = new AlarmStateTracker(repository);
        AlarmTransition recovered = restarted.evaluate("device", "point", rule, 9D, 3_000L);
        assertEquals(2_000L, recovered.lastOccurredAt());
        assertEquals(AlarmLifecycleState.RECOVERED,
                repository.findByAlarmId(activated.alarmId()).orElseThrow().getLifecycleState());
        assertTrue(new AlarmStateTracker(repository).acknowledgeByAlarmId(
                activated.alarmId(), "operator", 2_500L, "handled", "request-1"));
        assertEquals(AlarmLifecycleState.RECOVERED,
                repository.findByAlarmId(activated.alarmId()).orElseThrow().getLifecycleState());
        AlarmTransition next = restarted.evaluate("device", "point", rule, 12D, 4_000L);
        assertTrue(!activated.alarmId().equals(next.alarmId()));
        assertTrue(!tracker.acknowledgeByAlarmId(activated.alarmId(), "operator", 5_000L, "late", "request-2"));
    }

    @Test
    void shouldRestoreActiveAndAcknowledgedStateAfterRestart() {
        AlarmRule rule = new AlarmRule();
        rule.setRuleId("pressure-high");
        rule.setOperator(">");
        rule.setThreshold(10D);
        rule.setDuration(0);
        InMemoryAlarmStateRepository repository = new InMemoryAlarmStateRepository();

        AlarmStateTracker firstTracker = new AlarmStateTracker(repository);
        AlarmTransition activated = firstTracker.evaluate(
                "device-2", "pressure", rule, 12D, 1_000L);
        assertEquals(AlarmTransitionType.ACTIVATED, activated.type());

        AlarmStateTracker restartedTracker = new AlarmStateTracker(repository);
        AlarmTransition active = restartedTracker.evaluate(
                "device-2", "pressure", rule, 12D, 2_000L);
        assertEquals(AlarmTransitionType.NONE, active.type());
        assertEquals(AlarmLifecycleState.ACTIVE, active.state());
        assertTrue(restartedTracker.acknowledge("device-2", "pressure", rule));

        AlarmStateTracker acknowledgedTracker = new AlarmStateTracker(repository);
        AlarmTransition recovered = acknowledgedTracker.evaluate(
                "device-2", "pressure", rule, 9D, 3_000L);
        assertEquals(AlarmTransitionType.RECOVERED, recovered.type());
        assertEquals(activated.alarmId(), recovered.alarmId());
    }
}
