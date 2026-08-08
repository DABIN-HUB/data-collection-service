package com.wangbin.collector.core.report.service;

import com.wangbin.collector.common.config.DistributedLock;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.cloud.aggregation.CloudPackReportAssembler;
import com.wangbin.collector.core.cloud.service.CloudDeviceIdentityService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.service.support.GatewayRateLimiter;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.shadow.DeviceShadow;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class CacheReportServiceTest {

    @Test
    void splitSnapshotKeepsQualityAndTimestampAligned() {
        ReportProperties props = new ReportProperties();
        props.setMaxPropertiesPerMessage(2);
        props.setMaxPayloadBytes(1024);
        CacheReportService service = new CacheReportService(null, props, null, null, null, null,
                new CloudPackReportAssembler(), null, null, null, null, null);

        ReportData snapshot = new ReportData();
        snapshot.setDeviceId("dev-test");
        snapshot.setTimestamp(1000L);
        snapshot.setPointCode("snapshot");
        snapshot.addMetadata("schemaVersion", props.getSchemaVersion());
        snapshot.addMetadata("seq", 1L);
        snapshot.addProperty("f1", 1.0, 101L, "GOOD");
        snapshot.addProperty("f2", 2.0, 102L, "WARNING");
        snapshot.addProperty("f3", 3.0, 103L, "GOOD");

        List<ReportData> chunks = service.splitSnapshot(snapshot);
        assertEquals(2, chunks.size());

        ReportData chunk1 = chunks.get(0);
        assertEquals(Map.of("f1", 1.0, "f2", 2.0), chunk1.getProperties());
        assertEquals(101L, chunk1.getPropertyTs().get("f1"));
        assertEquals("WARNING", chunk1.getPropertyQuality().get("f2"));
        assertEquals(0, ((Number) chunk1.getMetadata().get("chunkIndex")).intValue());
        assertEquals(2, ((Number) chunk1.getMetadata().get("chunkTotal")).intValue());
        assertNotNull(chunk1.getMetadata().get("batchId"));

        ReportData chunk2 = chunks.get(1);
        assertEquals(Map.of("f3", 3.0), chunk2.getProperties());
        assertEquals(103L, chunk2.getPropertyTs().get("f3"));
        assertEquals(1, ((Number) chunk2.getMetadata().get("chunkIndex")).intValue());
        assertEquals(2, ((Number) chunk2.getMetadata().get("chunkTotal")).intValue());
        assertNotNull(chunk2.getMetadata().get("batchId"));
        assertEquals(chunk1.getMetadata().get("batchId"), chunk2.getMetadata().get("batchId"));
    }

    @Test
    void splitSnapshotShouldPreservePropertyMetadata() {
        ReportProperties props = new ReportProperties();
        props.setMaxPropertiesPerMessage(1);
        props.setMaxPayloadBytes(1024);
        CacheReportService service = new CacheReportService(null, props, null, null, null, null,
                new CloudPackReportAssembler(), null, null, null, null, null);

        ReportData snapshot = new ReportData();
        snapshot.setDeviceId("dev-test");
        snapshot.setTimestamp(1000L);
        snapshot.setPointCode("snapshot");
        snapshot.addProperty("f1", List.of("analogInput:1"), 101L, "GOOD",
                Map.of("bacnetComplexValue", true, "bacnetValueType", "OBJECT_LIST"));
        snapshot.addProperty("f2", 2.0, 102L, "GOOD");

        List<ReportData> chunks = service.splitSnapshot(snapshot);

        assertEquals(Boolean.TRUE, chunks.get(0).getPropertyMetadata().get("f1").get("bacnetComplexValue"));
        assertEquals("OBJECT_LIST", chunks.get(0).getPropertyMetadata().get("f1").get("bacnetValueType"));
    }

    @Test
    void reportDataShouldPreserveBacnetPropertyMetadataIntoChunks() {
        DataPoint point = new DataPoint();
        point.setDeviceId("dev-bacnet-report");
        point.setPointId("p1");
        point.setPointCode("objectList");
        point.setPointName("objectList");
        point.setStatus(1);
        point.setAdditionalConfig(new java.util.HashMap<>(Map.of("reportField", "objectList")));

        ProcessResult result = new ProcessResult();
        result.setSuccess(true);
        result.setProcessedValue(List.of("analogInput:1", "analogOutput:2"));
        result.setQuality(QualityEnum.GOOD.getCode());
        result.addMetadata("source", "poll");
        result.addMetadata("bacnetComplexValue", true);
        result.addMetadata("bacnetValueType", "OBJECT_LIST");
        result.addMetadata("bacnetValueMetadata", Map.of("semantic", "objectList", "count", 2));

        ReportData data = ReportData.buildReportData("dev-bacnet-report", "thing.property.post", point, result);

        assertNotNull(data);
        assertEquals(List.of("analogInput:1", "analogOutput:2"), data.getProperties().get("objectList"));
        assertEquals(Boolean.TRUE, data.getPropertyMetadata().get("objectList").get("bacnetComplexValue"));
        assertEquals("OBJECT_LIST", data.getPropertyMetadata().get("objectList").get("bacnetValueType"));
    }

    @Test
    void cacheReportServiceShouldRetryWithBackoffWhenExecutorRejected() {
        ReportProperties props = baseProps();
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        when(reportManager.reportAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(rejectedResult()));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(mock(ScheduledFuture.class));

        CacheReportService service = createService(reportManager, props, shadowManager, taskScheduler);
        Object tracker = createTracker("dev-retry", 8);
        ReportData data = chunk("dev-retry", "f1");
        ReportConfig config = validConfig();

        ReflectionTestUtils.invokeMethod(service, "dispatch", data, config, false, tracker, 0);

        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(shadowManager, never()).markReportedValuesChunk(any(), any());
    }

    @Test
    void cacheReportServiceShouldCapDeferredRetries() {
        ReportProperties props = baseProps();
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ReportData data = chunk("dev-deferred", "f1");
        ReportConfig config = validConfig();
        ReportResult deferred = ReportResult.error(data.getPointCode(), "offline", config.getTargetId());
        deferred.addMetadata("deferred", true);

        when(reportManager.reportAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(deferred));

        CacheReportService service = createService(reportManager, props, shadowManager, taskScheduler);
        Object tracker = createTracker("dev-deferred", 8);

        ReflectionTestUtils.invokeMethod(service, "dispatch", data, config, false, tracker, 0);
        ReflectionTestUtils.invokeMethod(service, "dispatch", data, config, false, tracker, 1);
        ReflectionTestUtils.invokeMethod(service, "dispatch", data, config, false, tracker, 2);

        verify(taskScheduler, times(props.getRetryTimes())).schedule(any(Runnable.class), any(Instant.class));
        verify(shadowManager, never()).markReportedValuesChunk(any(), any());
    }

    @Test
    void cacheReportServiceShouldKeepDirtyDeviceWhenPartialChunkFails() {
        ReportProperties props = baseProps();
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        CacheReportService service = createService(reportManager, props, shadowManager, taskScheduler);
        Object tracker = createTracker("dev-partial", 8);
        ReportConfig config = validConfig();

        ReportData successChunk = chunk("dev-partial", "f1");
        ReportData failedChunk = chunk("dev-partial", "f2");

        ReflectionTestUtils.invokeMethod(
                service,
                "handleChunkResult",
                successChunk,
                ReportResult.success(successChunk.getPointCode(), config.getTargetId()),
                null,
                tracker,
                "chunk-1",
                config,
                false,
                0
        );
        ReflectionTestUtils.invokeMethod(
                service,
                "handleChunkResult",
                failedChunk,
                ReportResult.error(failedChunk.getPointCode(), "fatal", config.getTargetId()),
                null,
                tracker,
                "chunk-2",
                config,
                false,
                props.getRetryTimes() + 1
        );

        verify(shadowManager).markReportedValuesChunk(eq("dev-partial"), eq(successChunk.getProperties()));
        verify(shadowManager, never()).markReportedWindowCommitted(eq("dev-partial"), anyLong(), anyLong());
    }

    @Test
    void cacheReportServiceShouldLimitPendingChunksPerDevice() {
        ReportProperties props = baseProps();
        props.setMaxPendingChunksPerDevice(1);
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        CompletableFuture<ReportResult> gate = new CompletableFuture<>();

        when(reportManager.reportAsync(any(), any())).thenReturn(gate);

        CacheReportService service = createService(reportManager, props, shadowManager, taskScheduler);
        Object tracker = createTracker("dev-limit", 1);
        ReportConfig config = validConfig();
        ReportData first = chunk("dev-limit", "f1");
        ReportData second = chunk("dev-limit", "f2");

        ReflectionTestUtils.invokeMethod(service, "dispatch", first, config, false, tracker, 0);
        ReflectionTestUtils.invokeMethod(service, "dispatch", second, config, false, tracker, 0);

        verify(reportManager).reportAsync(eq(first), eq(config));
        verify(reportManager, never()).reportAsync(eq(second), eq(config));
        gate.complete(ReportResult.success(first.getPointCode(), config.getTargetId()));
    }

    @Test
    void outboxStoreFailureShouldPreventNetworkSend() {
        ReportProperties props = baseProps();
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        CloudOutboxService cloudOutboxService = mock(CloudOutboxService.class);
        when(cloudOutboxService.isEnabled()).thenReturn(true);
        when(cloudOutboxService.stage(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        CacheReportService service = createService(
                reportManager, props, shadowManager, taskScheduler, null, cloudOutboxService);
        Object tracker = createTracker("dev-outbox-fail", 8);
        ReportData data = chunk("dev-outbox-fail", "f1");
        ReportConfig config = validConfig();

        ReflectionTestUtils.invokeMethod(service, "dispatch", data, config, false, tracker, 0);

        verify(cloudOutboxService).stage(any(), anyLong(), anyLong(), anyLong(), any());
        verify(reportManager, never()).reportAsync(any(), any());
        verify(shadowManager, never()).markReportedValuesChunk(any(), any());
    }

    @Test
    void cacheReportServiceShouldAvoidDuplicateFlushAcrossInstances() {
        ReportProperties props = baseProps();
        ReportManager reportManager = mock(ReportManager.class);
        ShadowManager shadowManager = mock(ShadowManager.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        DistributedLock distributedLock = mock(DistributedLock.class);

        when(distributedLock.tryLock(any(), anyLong(), any(TimeUnit.class))).thenReturn(Optional.empty());

        CacheReportService service = createService(reportManager, props, shadowManager, taskScheduler, distributedLock);
        ReflectionTestUtils.setField(service, "shadowGatewayMapping",
                new ConcurrentHashMap<>(Map.of("dev-lock", "gw-lock")));
        when(shadowManager.getShadow("dev-lock")).thenReturn(new NonEmptyDeviceShadow("dev-lock"));

        ReflectionTestUtils.invokeMethod(service, "flushDevice", "dev-lock");

        verify(distributedLock).tryLock(any(), anyLong(), any(TimeUnit.class));
        verifyNoInteractions(reportManager);
        verify(shadowManager, never()).clearDirty("dev-lock");
    }

    @Test
    void strictGatewayModeShouldBuildSingleSubDevicePropertyPack() {
        ReportProperties props = baseProps();
        props.getMqtt().setGatewayProductKey("pk-gw");
        props.getMqtt().setGatewayDeviceName("gw-1");
        props.getCloud().setSubDeviceTopicProxyEnabled(false);
        CacheReportService service = createService(
                mock(ReportManager.class),
                props,
                mock(ShadowManager.class),
                mock(TaskScheduler.class)
        );
        ReportConfig config = validConfig();
        config.getParams().put("gatewayProductKey", "pk-gw");
        config.getParams().put("gatewayDeviceName", "gw-1");

        ReportData snapshot = new ReportData();
        snapshot.setDeviceId("sub-1");
        snapshot.setTimestamp(1000L);
        snapshot.addMetadata("productKey", "pk-sub");
        snapshot.addMetadata("shadowKey", "pk-sub/sub-1");
        snapshot.addProperty("temperature", 36.5, 1000L, "GOOD");

        Boolean strictMode = ReflectionTestUtils.invokeMethod(
                service,
                "shouldUseGatewayTopicForSubDevice",
                snapshot,
                config,
                "gw-1"
        );
        ReportData propertyPack = ReflectionTestUtils.invokeMethod(
                service,
                "buildSingleSubDevicePack",
                snapshot,
                config,
                "gw-1"
        );

        assertEquals(Boolean.TRUE, strictMode);
        assertNotNull(propertyPack);
        assertEquals("gw-1", propertyPack.getDeviceId());
        assertEquals("pk-gw", propertyPack.getMetadata().get("productKey"));
        Map<?, ?> pack = (Map<?, ?>) propertyPack.getMetadata().get("propertyPack");
        List<?> subDevices = (List<?>) pack.get("subDevices");
        Map<?, ?> subDevice = (Map<?, ?>) subDevices.get(0);
        Map<?, ?> identity = (Map<?, ?>) subDevice.get("identity");
        Map<?, ?> properties = (Map<?, ?>) subDevice.get("properties");
        assertEquals("pk-sub", identity.get("productKey"));
        assertEquals("sub-1", identity.get("deviceName"));
        assertEquals(36.5, properties.get("temperature"));
    }

    @Test
    void proxyModeShouldKeepSubDeviceTopicPayload() {
        ReportProperties props = baseProps();
        props.getCloud().setSubDeviceTopicProxyEnabled(true);
        CacheReportService service = createService(
                mock(ReportManager.class),
                props,
                mock(ShadowManager.class),
                mock(TaskScheduler.class)
        );
        ReportConfig config = validConfig();
        config.getParams().put("gatewayProductKey", "pk-gw");
        config.getParams().put("gatewayDeviceName", "gw-1");

        ReportData snapshot = new ReportData();
        snapshot.setDeviceId("sub-1");
        snapshot.addMetadata("productKey", "pk-sub");
        snapshot.addProperty("temperature", 36.5, 1000L, "GOOD");

        Boolean strictMode = ReflectionTestUtils.invokeMethod(
                service,
                "shouldUseGatewayTopicForSubDevice",
                snapshot,
                config,
                "gw-1"
        );

        assertEquals(Boolean.FALSE, strictMode);
    }

    private CacheReportService createService(ReportManager reportManager,
                                             ReportProperties props,
                                             ShadowManager shadowManager,
                                             TaskScheduler taskScheduler) {
        return createService(reportManager, props, shadowManager, taskScheduler, null);
    }

    private CacheReportService createService(ReportManager reportManager,
                                             ReportProperties props,
                                             ShadowManager shadowManager,
                                             TaskScheduler taskScheduler,
                                             DistributedLock distributedLock) {
        return createService(reportManager, props, shadowManager, taskScheduler, distributedLock, null);
    }

    private CacheReportService createService(ReportManager reportManager,
                                             ReportProperties props,
                                             ShadowManager shadowManager,
                                             TaskScheduler taskScheduler,
                                             DistributedLock distributedLock,
                                             CloudOutboxService cloudOutboxService) {
        CloudDeviceIdentityService cloudDeviceIdentityService = mock(CloudDeviceIdentityService.class);
        ReportConfigProvider reportConfigProvider = mock(ReportConfigProvider.class);
        GatewayRateLimiter gatewayRateLimiter = mock(GatewayRateLimiter.class);
        when(gatewayRateLimiter.tryAcquire(anyBoolean())).thenReturn(true);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(mock(ScheduledFuture.class));
        return new CacheReportService(
                reportManager,
                props,
                shadowManager,
                cloudDeviceIdentityService,
                reportConfigProvider,
                gatewayRateLimiter,
                new CloudPackReportAssembler(),
                distributedLock,
                taskScheduler,
                null,
                null,
                cloudOutboxService
        );
    }

    private ReportProperties baseProps() {
        ReportProperties props = new ReportProperties();
        props.setRetryTimes(2);
        props.setRetryBackoffMs(100);
        props.setMaxRetryBackoffMs(500);
        props.setRetryJitterEnabled(false);
        props.setMaxPendingChunksPerDevice(8);
        props.setIntervalMs(1000);
        return props;
    }

    private ReportResult rejectedResult() {
        ReportResult result = ReportResult.error("f1", "overloaded", "target-1");
        result.addMetadata("retryable", true);
        result.addMetadata("rejected", true);
        result.addMetadata("reason", "executor_rejected");
        return result;
    }

    private ReportConfig validConfig() {
        ReportConfig config = new ReportConfig();
        config.setProtocol("MQTT");
        config.setTargetId("target-1");
        config.setHost("localhost");
        config.setPort(1883);
        config.setParams(new HashMap<>());
        return config;
    }

    private ReportData chunk(String deviceId, String field) {
        ReportData data = new ReportData();
        data.setDeviceId(deviceId);
        data.setPointCode(field);
        data.setTimestamp(System.currentTimeMillis());
        data.addMetadata("batchId", "batch-" + deviceId);
        data.addMetadata("chunkIndex", field);
        data.addMetadata("seq", 1L);
        data.addProperty(field, 1, System.currentTimeMillis(), "GOOD");
        return data;
    }

    private Object createTracker(String deviceId, int maxPendingChunks) {
        Class<?> trackerClass = List.of(CacheReportService.class.getDeclaredClasses()).stream()
                .filter(clazz -> clazz.getSimpleName().equals("FlushTracker"))
                .findFirst()
                .orElseThrow();
        try {
            Constructor<?> constructor = trackerClass.getDeclaredConstructor(String.class, long.class, long.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(deviceId, 1L, 2L, 2, maxPendingChunks);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class NonEmptyDeviceShadow extends DeviceShadow {

        private NonEmptyDeviceShadow(String deviceId) {
            super(deviceId);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
