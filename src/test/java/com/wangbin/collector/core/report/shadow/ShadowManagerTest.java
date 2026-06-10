package com.wangbin.collector.core.report.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowManagerTest {

    private ReportProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReportProperties();
        properties.setMinReportIntervalMs(2000);
        properties.setEventMinIntervalMs(2000);
    }

    @Test
    void changeTriggerRespectsThresholdAndMinInterval() {
        ShadowManager manager = new ShadowManager(properties);
        DataPoint point = createPoint("dev-change", "phaseA", Map.of(
                "reportEnabled", true,
                "reportField", "phaseA",
                "changeThreshold", 2.0,
                "changeMinIntervalMs", 2000L
        ));

        ProcessResult r1 = buildResult(10d, QualityEnum.GOOD.getCode());
        ShadowManager.ShadowUpdateResult first = manager.apply("dev-change", point, r1);
        assertFalse(first.changeTriggered());
        manager.markReportedValues("dev-change", Map.of("phaseA", 10d));

        ProcessResult r2 = buildResult(13d, QualityEnum.GOOD.getCode());
        ShadowManager.ShadowUpdateResult second = manager.apply("dev-change", point, r2);
        assertTrue(second.changeTriggered());
        manager.markReportedValues("dev-change", Map.of("phaseA", 13d));

        ProcessResult r3 = buildResult(16d, QualityEnum.GOOD.getCode());
        ShadowManager.ShadowUpdateResult third = manager.apply("dev-change", point, r3);
        assertFalse(third.changeTriggered(), "should be blocked by min interval");

        DeviceShadow shadow = manager.getShadow("dev-change");
        shadow.markChangeTrigger(point.getReportField() + ":change", System.currentTimeMillis() - 5000);

        ProcessResult r4 = buildResult(19d, QualityEnum.GOOD.getCode());
        ShadowManager.ShadowUpdateResult fourth = manager.apply("dev-change", point, r4);
        assertTrue(fourth.changeTriggered(), "should trigger once interval elapsed");
    }

    @Test
    void metadataEventTriggerAndQualityFallbackWork() {
        ShadowManager manager = new ShadowManager(properties);
        DataPoint point = createPoint("dev-event", "ia", Map.of(
                "reportEnabled", true,
                "reportField", "ia",
                "eventEnabled", true,
                "eventMinIntervalMs", 2000L
        ));

        ProcessResult metaEvent = buildResult(5d, QualityEnum.GOOD.getCode());
        metaEvent.setSuccess(true);
        metaEvent.addMetadata("eventTriggered", true);
        metaEvent.addMetadata("eventType", "OVER_LIMIT");
        metaEvent.addMetadata("eventLevel", "CRITICAL");
        metaEvent.addMetadata("eventMessage", "over threshold");

        ShadowManager.ShadowUpdateResult first = manager.apply("dev-event", point, metaEvent);
        assertNotNull(first.eventInfo());
        assertEquals("OVER_LIMIT", first.eventInfo().eventType());

        ShadowManager.ShadowUpdateResult second = manager.apply("dev-event", point, metaEvent);
        assertNull(second.eventInfo(), "event should be throttled by min interval");

        DeviceShadow shadow = manager.getShadow("dev-event");
        String eventKey = point.getReportField() + "|" + first.eventInfo().eventType();
        long past = System.currentTimeMillis() - 5000;
        shadow.markEventTrigger(eventKey, past);
        String signature = first.eventInfo().eventType() + ":" +
                java.util.Objects.hash(first.eventInfo().message(), first.eventInfo().ruleId(), first.eventInfo().ruleName());
        shadow.markEventSignature(signature, past);

        ShadowManager.ShadowUpdateResult third = manager.apply("dev-event", point, metaEvent);
        assertNotNull(third.eventInfo(), "event should fire again after interval");

        ProcessResult badQuality = buildResult(7d, QualityEnum.BAD.getCode());
        badQuality.setSuccess(false);
        ShadowManager.ShadowUpdateResult qualityEvent = manager.apply("dev-event", point, badQuality);
        assertNotNull(qualityEvent.eventInfo());
        assertEquals("QUALITY", qualityEvent.eventInfo().eventType());
    }

    @Test
    @SuppressWarnings("unchecked")
    void desiredStateBuildsDeltaAndClearsWhenReportedMatches() {
        ShadowManager manager = new ShadowManager(properties);
        DataPoint point = createPoint("dev-shadow", "temperature", Map.of(
                "reportEnabled", true,
                "reportField", "temperature"
        ));

        Map<String, Object> initial = manager.updateDesired("dev-shadow", Map.of("temperature", 26.0), "test");
        Map<String, Object> initialState = (Map<String, Object>) initial.get("state");
        assertEquals(Map.of("temperature", 26.0), initialState.get("desired"));
        assertEquals(Map.of("temperature", 26.0), initialState.get("delta"));

        manager.apply("dev-shadow", point, buildResult(25.0, QualityEnum.GOOD.getCode()));
        Map<String, Object> mismatch = manager.getShadowDocument("dev-shadow");
        Map<String, Object> mismatchState = (Map<String, Object>) mismatch.get("state");
        assertEquals(Map.of("temperature", 25.0), mismatchState.get("reported"));
        assertEquals(Map.of("temperature", 26.0), mismatchState.get("desired"));
        assertEquals(Map.of("temperature", 26.0), mismatchState.get("delta"));

        manager.apply("dev-shadow", point, buildResult(26.0, QualityEnum.GOOD.getCode()));
        Map<String, Object> matched = manager.getShadowDocument("dev-shadow");
        Map<String, Object> matchedState = (Map<String, Object>) matched.get("state");
        assertEquals(Map.of("temperature", 26.0), matchedState.get("reported"));
        assertEquals(Map.of(), matchedState.get("desired"));
        assertEquals(Map.of(), matchedState.get("delta"));
    }

    @Test
    void desiredUpdateRejectsStaleLocalExpectedVersion() {
        ShadowManager manager = new ShadowManager(properties);

        manager.updateDesired("dev-version", Map.of("temperature", 26.0), "test");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.updateDesired("dev-version", Map.of("temperature", 27.0), "test", 0L));
        assertTrue(ex.getMessage().contains("shadow version conflict"));
    }

    @Test
    void desiredUpdateUsesRedisCasAndRejectsRemoteConflict() {
        ShadowManager manager = new ShadowManager(properties);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(stringRedisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenReturn(List.of(0L, 7L));
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(manager, "objectMapper", new ObjectMapper());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.updateDesired("dev-cas", Map.of("temperature", 26.0), "test"));

        assertTrue(ex.getMessage().contains("expected=0"));
        assertTrue(ex.getMessage().contains("actual=7"));
    }

    @Test
    void reportedPersistenceUsesConfiguredTtl() {
        properties.getShadow().setTtlSeconds(60);
        ShadowManager manager = new ShadowManager(properties);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(manager, "objectMapper", new ObjectMapper());
        DataPoint point = createPoint("dev-ttl", "temperature", Map.of(
                "reportEnabled", true,
                "reportField", "temperature"
        ));

        manager.apply("dev-ttl", point, buildResult(25.0, QualityEnum.GOOD.getCode()));

        verify(valueOperations).set(eq("collector:shadow:dev-ttl"), anyString(), eq(60000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void desiredUpdateAutoMergesRemoteShadowAfterCasConflict() throws Exception {
        ShadowManager manager = new ShadowManager(properties);
        manager.updateDesired("dev-merge", Map.of("localOnly", 1), "seed");

        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(
                remoteDocument("dev-merge", 2L, Map.of("remoteOnly", 2))));
        when(stringRedisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenReturn(List.of(0L, 2L), List.of(1L, 3L));
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(manager, "objectMapper", objectMapper);

        Map<String, Object> document = manager.updateDesired("dev-merge", Map.of("localOnly", 3), "test");

        Map<String, Object> state = (Map<String, Object>) document.get("state");
        Map<String, Object> desired = (Map<String, Object>) state.get("desired");
        assertEquals(2, desired.get("remoteOnly"));
        assertEquals(3, desired.get("localOnly"));
        assertEquals(3L, document.get("version"));
        verify(stringRedisTemplate, times(2)).execute(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());
    }

    @Test
    void successfulDesiredUpdateWritesHistoryAudit() {
        ShadowManager manager = new ShadowManager(properties);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(stringRedisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenReturn(List.of(1L, 1L));
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(manager, "objectMapper", new ObjectMapper());

        manager.updateDesired("dev-history", Map.of("temperature", 26.0), "test");

        ArgumentCaptor<String> historyCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq("collector:shadow:history:dev-history"), historyCaptor.capture());
        String history = historyCaptor.getValue();
        Assertions.assertAll(
                () -> assertTrue(history.contains("\"action\":\"desired_update\"")),
                () -> assertTrue(history.contains("\"version\":1")),
                () -> assertTrue(history.contains("\"baseVersion\":0"))
        );
        verify(listOperations).trim(eq("collector:shadow:history:dev-history"), eq(0L), eq(99L));
        verify(stringRedisTemplate).expire(eq("collector:shadow:history:dev-history"),
                eq(604800L), eq(TimeUnit.SECONDS));
    }

    private DataPoint createPoint(String deviceId, String alias, Map<String, Object> config) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(alias + "-id");
        point.setPointCode(alias + "-code");
        point.setPointAlias(alias);
        point.setAdditionalConfig(new HashMap<>(config));
        return point;
    }

    private ProcessResult buildResult(double value, int quality) {
        ProcessResult result = new ProcessResult();
        result.setSuccess(true);
        result.setProcessedValue(value);
        result.setQuality(quality);
        return result;
    }

    private Map<String, Object> remoteDocument(String deviceId, long version, Map<String, Object> desired) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("deviceId", deviceId);
        doc.put("version", version);
        doc.put("timestamp", System.currentTimeMillis());
        doc.put("createdAt", System.currentTimeMillis());
        doc.put("lastReportAt", System.currentTimeMillis());
        doc.put("lastWindowStart", 0L);
        doc.put("lastWindowEnd", 0L);

        Map<String, Object> state = new HashMap<>();
        state.put("reported", Map.of());
        state.put("desired", desired);
        state.put("delta", desired);
        doc.put("state", state);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reported", Map.of());
        metadata.put("desired", Map.of());
        doc.put("metadata", metadata);
        return doc;
    }
}
