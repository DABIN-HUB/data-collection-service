package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.CompactAllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimeDeltaResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimePointPayload;
import com.wangbin.collector.api.controller.dto.DeviceBriefResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.AllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeDataQueryApplicationServiceTest {

    private MultiLevelCacheManager cacheManager;
    private ConfigManager configManager;
    private PointRuntimeStateService pointRuntimeStateService;
    private RealtimeChangeTracker realtimeChangeTracker;
    private RealtimeDataQueryApplicationService service;

    @BeforeEach
    void setUp() {
        cacheManager = mock(MultiLevelCacheManager.class);
        configManager = mock(ConfigManager.class);
        pointRuntimeStateService = mock(PointRuntimeStateService.class);
        realtimeChangeTracker = mock(RealtimeChangeTracker.class);
        when(realtimeChangeTracker.snapshotId()).thenReturn("snapshot-test");
        when(realtimeChangeTracker.configEpoch()).thenReturn(1L);
        when(realtimeChangeTracker.currentRevision()).thenReturn(0L);
        when(realtimeChangeTracker.capture()).thenReturn(new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 0L));
        when(realtimeChangeTracker.validateCursor("snapshot-test", 1L, 0L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(0L));
        service = new RealtimeDataQueryApplicationService(cacheManager, configManager, pointRuntimeStateService, realtimeChangeTracker);
    }

    @Test
    void getPointDataShouldReturnCachedValueAndRuntimeFields() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        when(configManager.getDataPointByPointId("dev-1", "p-1")).thenReturn(point);
        when(cacheManager.get(any(CacheKey.class))).thenReturn("36.5");
        when(pointRuntimeStateService.snapshot("dev-1", point))
                .thenReturn(new PointRuntimeStateSnapshot(5000L, 2, "35.1", 0.12D, 123456L));

        PointRealtimeResponse response = service.getPointData("dev-1", "p-1");

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertNotNull(response.getTimestamp());
        PointRealtimePayload payload = response.getData();
        assertEquals("p-1", payload.getPointId());
        assertEquals("temperature", payload.getPointCode());
        assertEquals("36.5", payload.getValue());
        assertEquals("36.5", payload.getRawValue());
        assertEquals(Boolean.TRUE, payload.getHasCachedValue());
        assertEquals(5000L, payload.getCurrentCollectionInterval());
        assertEquals(2, payload.getStableCount());
        assertEquals("35.1", payload.getLastValue());
        assertEquals(0.12D, payload.getChangeRate());
        assertEquals(123456L, payload.getLastAdjustTime());
        verify(cacheManager).get(argThat(key -> "data:dev-1:p-1".equals(key.getFullKey())));
    }

    @Test
    void getPointDataShouldReturnErrorWhenPointMissingAndNotQueryCache() {
        when(configManager.getDataPointByPointId("dev-1", "missing")).thenReturn(null);

        PointRealtimeResponse response = service.getPointData("dev-1", "missing");

        assertEquals("error", response.getStatus());
        assertEquals("数据点不存在", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("missing", response.getPointId());
        assertNotNull(response.getTimestamp());
        verify(cacheManager, never()).get(any(CacheKey.class));
    }

    @Test
    void getPointDataShouldReturnErrorWhenCacheThrows() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        when(configManager.getDataPointByPointId("dev-1", "p-1")).thenReturn(point);
        when(cacheManager.get(any(CacheKey.class))).thenThrow(new IllegalStateException("cache down"));

        PointRealtimeResponse response = service.getPointData("dev-1", "p-1");

        assertEquals("error", response.getStatus());
        assertEquals("查询失败: cache down", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
    }

    @Test
    void getDeviceDataShouldReturnPayloadMapUsingPointIdOrder() {
        DataPoint first = point("dev-1", "p-1", "temperature");
        DataPoint second = point("dev-1", "p-2", "humidity");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(first, second));
        when(pointRuntimeStateService.snapshot("dev-1", first))
                .thenReturn(new PointRuntimeStateSnapshot(1000L, 1, null, 0D, 0L));
        when(pointRuntimeStateService.snapshot("dev-1", second))
                .thenReturn(new PointRuntimeStateSnapshot(2000L, 2, "old", 0.3D, 456L));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> {
            List<CacheKey> keys = invocation.getArgument(0);
            return Map.of(keys.get(0), "v1", keys.get(1), "v2");
        });

        DeviceRealtimeDataResponse response = service.getDeviceData("dev-1", null);

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals(2, response.getDataCount());
        assertEquals(List.of("p-1", "p-2"), response.getData().keySet().stream().toList());
        assertEquals("v1", response.getData().get("p-1").getValue());
        assertEquals("v2", response.getData().get("p-2").getValue());
        assertEquals(2000L, response.getData().get("p-2").getCurrentCollectionInterval());
        verify(cacheManager).getAll(argThat(keys -> keys.size() == 2
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())
                && "data:dev-1:p-2".equals(keys.get(1).getFullKey())));
    }

    @Test
    void getDeviceDataShouldRespectPointIdsFilter() {
        DataPoint first = point("dev-1", "p-1", "temperature");
        DataPoint second = point("dev-1", "p-2", "humidity");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(first, second));
        when(pointRuntimeStateService.snapshot("dev-1", second))
                .thenReturn(new PointRuntimeStateSnapshot(2000L, 0, null, 0D, 0L));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), "selected"));

        DeviceRealtimeDataResponse response = service.getDeviceData("dev-1", List.of("p-2"));

        assertEquals("success", response.getStatus());
        assertEquals(1, response.getDataCount());
        assertEquals(List.of("p-2"), response.getData().keySet().stream().toList());
        assertEquals("selected", response.getData().get("p-2").getValue());
    }

    @Test
    void getDeviceDataShouldReturnErrorWhenDeviceHasNoPoints() {
        when(configManager.getDataPoints("empty")).thenReturn(List.of());

        DeviceRealtimeDataResponse response = service.getDeviceData("empty", null);

        assertEquals("error", response.getStatus());
        assertEquals("设备不存在或无数据点", response.getMessage());
        assertNotNull(response.getTimestamp());
        verify(cacheManager, never()).getAll(anyList());
    }

    @Test
    void getAllRealtimeDataShouldBatchAllDeviceCacheKeysOnceAndGroupByDevice() {
        DataPoint dev1Point1 = point("dev-1", "p-1", "temperature");
        DataPoint dev1Point2 = point("dev-1", "p-2", "humidity");
        DataPoint dev2Point1 = point("dev-2", "p-3", "pressure");
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-1", "dev-2"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(dev1Point1, dev1Point2));
        when(configManager.getDataPoints("dev-2")).thenReturn(List.of(dev2Point1));
        when(pointRuntimeStateService.snapshot("dev-1", dev1Point1)).thenReturn(new PointRuntimeStateSnapshot(1000L, 1, null, 0D, 0L));
        when(pointRuntimeStateService.snapshot("dev-1", dev1Point2)).thenReturn(new PointRuntimeStateSnapshot(1000L, 1, null, 0D, 0L));
        when(pointRuntimeStateService.snapshot("dev-2", dev2Point1)).thenReturn(new PointRuntimeStateSnapshot(1000L, 1, null, 0D, 0L));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> {
            List<CacheKey> keys = invocation.getArgument(0);
            return Map.of(keys.get(0), "v1", keys.get(1), "v2", keys.get(2), "v3");
        });

        AllDeviceRealtimeDataResponse response = service.getAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(2, response.getDeviceCount());
        assertEquals(3, response.getDataCount());
        assertEquals(List.of("dev-1", "dev-2"), response.getDevices().stream().map(DeviceRealtimeDataResponse::getDeviceId).toList());
        assertEquals(2, response.getDevices().get(0).getDataCount());
        assertEquals(1, response.getDevices().get(1).getDataCount());
        assertEquals("v1", response.getDevices().get(0).getData().get("p-1").getValue());
        assertEquals("v2", response.getDevices().get(0).getData().get("p-2").getValue());
        assertEquals("v3", response.getDevices().get(1).getData().get("p-3").getValue());
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 3
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())
                && "data:dev-1:p-2".equals(keys.get(1).getFullKey())
                && "data:dev-2:p-3".equals(keys.get(2).getFullKey())));
    }

    @Test
    void getAllRealtimeDataShouldReturnSuccessWhenNoDevicesConfigured() {
        when(configManager.getAllDeviceIds()).thenReturn(List.of());

        AllDeviceRealtimeDataResponse response = service.getAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(0, response.getDeviceCount());
        assertEquals(0, response.getDataCount());
        assertEquals(List.of(), response.getDevices());
        assertNotNull(response.getTimestamp());
        verify(cacheManager, never()).getAll(anyList());
    }

    @Test
    void getAllRealtimeDataShouldKeepPerDeviceErrorWhenOneDeviceHasNoPoints() {
        DataPoint dev1Point1 = point("dev-1", "p-1", "temperature");
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-1", "empty"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(dev1Point1));
        when(configManager.getDataPoints("empty")).thenReturn(List.of());
        when(pointRuntimeStateService.snapshot("dev-1", dev1Point1)).thenReturn(new PointRuntimeStateSnapshot(1000L, 1, null, 0D, 0L));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), "v1"));

        AllDeviceRealtimeDataResponse response = service.getAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(2, response.getDeviceCount());
        assertEquals(1, response.getDataCount());
        assertEquals("success", response.getDevices().get(0).getStatus());
        assertEquals("error", response.getDevices().get(1).getStatus());
        assertEquals("empty", response.getDevices().get(1).getDeviceId());
        assertEquals("设备不存在或无数据点", response.getDevices().get(1).getMessage());
        assertEquals(0, response.getDevices().get(1).getDataCount());
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 1
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())));
    }

    @Test
    void getCompactDeviceDataShouldUseRowsAndSkipRuntimeSnapshot() {
        DataPoint first = point("dev-1", "p-1", "temperature");
        DataPoint second = point("dev-1", "p-2", "humidity");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(first, second));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> {
            List<CacheKey> keys = invocation.getArgument(0);
            return Map.of(keys.get(0), "v1", keys.get(1), "v2");
        });

        CompactDeviceRealtimeDataResponse response = service.getCompactDeviceData("dev-1");

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals(2, response.getDataCount());
        assertEquals(List.of("p-1", "p-2"), response.getRows().stream().map(CompactRealtimePointPayload::getPointId).toList());
        assertEquals(List.of("dev-1", "dev-1"), response.getRows().stream().map(CompactRealtimePointPayload::getDeviceId).toList());
        assertEquals("v1", response.getRows().get(0).getValue());
        assertEquals("v2", response.getRows().get(1).getValue());
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 2
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())
                && "data:dev-1:p-2".equals(keys.get(1).getFullKey())));
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactDeviceDataShouldUseAuthoritativeDeviceIdWhenPointDeviceIdMissing() {
        DataPoint point = point(null, "p-1", "temperature");
        when(configManager.getDataPoints("dev-authoritative")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), "v1"));

        CompactRealtimePointPayload row = service.getCompactDeviceData("dev-authoritative").getRows().get(0);

        assertEquals("dev-authoritative", row.getDeviceId());
        assertNull(point.getDeviceId());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactDeviceDataShouldPreserveProcessResultTableSemantics() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        ProcessResult result = new ProcessResult();
        result.setSuccess(true);
        result.setRawValue("raw-12.3");
        result.setProcessedValue(12.3D);
        result.setQuality(80);
        result.setQualityDescription("数据质量警告");
        result.setProcessorName("DataQualityProcessor");
        result.setProcessingTime(7L);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, 1800000000123L);
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), result));

        CompactRealtimePointPayload row = service.getCompactDeviceData("dev-1").getRows().get(0);

        assertEquals(12.3D, row.getValue());
        assertEquals(80, row.getQuality());
        assertEquals("数据质量警告", row.getQualityDescription());
        assertEquals("B", row.getQualityLevel());
        assertEquals(Boolean.TRUE, row.getQualityAcceptable());
        assertEquals(Boolean.TRUE, row.getQualityAvailable());
        assertEquals(Boolean.TRUE, row.getProcessSuccess());
        assertEquals(7L, row.getProcessingTime());
        assertEquals(1800000000123L, row.getLastUpdateTime());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactDeviceDataShouldMapPlainCachedValueWithoutQualityAssessment() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), 12.3D));

        CompactRealtimePointPayload row = service.getCompactDeviceData("dev-1").getRows().get(0);

        assertEquals(12.3D, row.getValue());
        assertEquals(Boolean.FALSE, row.getQualityAvailable());
        assertNull(row.getQuality());
        assertNull(row.getQualityDescription());
        assertNull(row.getProcessSuccess());
        assertNull(row.getProcessingTime());
        assertNull(row.getLastUpdateTime());
    }

    @Test
    void getCompactAllRealtimeDataShouldUseOneBulkCacheLookupAndFlatRows() {
        DataPoint dev1Point1 = point("dev-1", "p-1", "temperature");
        DataPoint dev1Point2 = point("dev-1", "p-2", "humidity");
        DataPoint dev2Point1 = point("dev-2", "p-3", "pressure");
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-1", "dev-2"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(dev1Point1, dev1Point2));
        when(configManager.getDataPoints("dev-2")).thenReturn(List.of(dev2Point1));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> {
            List<CacheKey> keys = invocation.getArgument(0);
            return Map.of(keys.get(0), "v1", keys.get(1), "v2", keys.get(2), "v3");
        });

        CompactAllDeviceRealtimeDataResponse response = service.getCompactAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(2, response.getDeviceCount());
        assertEquals(3, response.getDataCount());
        assertEquals(List.of("p-1", "p-2", "p-3"), response.getRows().stream().map(CompactRealtimePointPayload::getPointId).toList());
        assertEquals(List.of("success", "success"), response.getDevices().stream().map(device -> device.getStatus()).toList());
        assertEquals(List.of(2, 1), response.getDevices().stream().map(device -> device.getDataCount()).toList());
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 3
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())
                && "data:dev-1:p-2".equals(keys.get(1).getFullKey())
                && "data:dev-2:p-3".equals(keys.get(2).getFullKey())));
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactAllRealtimeDataShouldKeepZeroDeviceSemanticsWithoutCacheLookup() {
        when(configManager.getAllDeviceIds()).thenReturn(List.of());

        CompactAllDeviceRealtimeDataResponse response = service.getCompactAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(0, response.getDeviceCount());
        assertEquals(0, response.getDataCount());
        assertEquals(List.of(), response.getRows());
        assertEquals(List.of(), response.getDevices());
        verify(cacheManager, never()).getAll(anyList());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactAllRealtimeDataShouldRetainPerDeviceErrorWithoutFailingAggregate() {
        DataPoint dev1Point1 = point("dev-1", "p-1", "temperature");
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-1", "empty"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(dev1Point1));
        when(configManager.getDataPoints("empty")).thenReturn(List.of());
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), "v1"));

        CompactAllDeviceRealtimeDataResponse response = service.getCompactAllRealtimeData();

        assertEquals("success", response.getStatus());
        assertEquals(2, response.getDeviceCount());
        assertEquals(1, response.getDataCount());
        assertEquals(1, response.getRows().size());
        assertEquals("success", response.getDevices().get(0).getStatus());
        assertEquals("error", response.getDevices().get(1).getStatus());
        assertEquals("empty", response.getDevices().get(1).getDeviceId());
        assertEquals("设备不存在或无数据点", response.getDevices().get(1).getMessage());
        assertEquals(0, response.getDevices().get(1).getDataCount());
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 1
                && "data:dev-1:p-1".equals(keys.get(0).getFullKey())));
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactAllRealtimeDataShouldUseAuthoritativeDeviceIdWhenPointDeviceIdWrong() {
        DataPoint point = point("wrong-device", "p-1", "temperature");
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-a"));
        when(configManager.getDataPoints("dev-a")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> Map.of(invocation.<List<CacheKey>>getArgument(0).get(0), "v1"));

        CompactRealtimePointPayload row = service.getCompactAllRealtimeData().getRows().get(0);

        assertEquals("dev-a", row.getDeviceId());
        assertEquals("wrong-device", point.getDeviceId());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void getCompactDeviceDataShouldReturnErrorRowsForDeviceWithNoPoints() {
        when(configManager.getDataPoints("empty")).thenReturn(List.of());

        CompactDeviceRealtimeDataResponse response = service.getCompactDeviceData("empty");

        assertEquals("error", response.getStatus());
        assertEquals("设备不存在或无数据点", response.getMessage());
        assertEquals("empty", response.getDeviceId());
        assertEquals(0, response.getDataCount());
        assertEquals(List.of(), response.getRows());
        verify(cacheManager, never()).getAll(anyList());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void compactFullResponseShouldExposeCursorAndCaptureBeforeCacheRead() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        when(realtimeChangeTracker.capture()).thenReturn(new RealtimeChangeTracker.SnapshotCursor("snapshot-full", 3L, 9L));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenReturn(Map.of());

        CompactDeviceRealtimeDataResponse response = service.getCompactDeviceData("dev-1");

        assertEquals("snapshot-full", response.getSnapshotId());
        assertEquals(3L, response.getConfigEpoch());
        assertEquals(9L, response.getRevision());
        InOrder inOrder = inOrder(realtimeChangeTracker, cacheManager);
        inOrder.verify(realtimeChangeTracker).capture();
        inOrder.verify(cacheManager).getAll(anyList());
    }

    @Test
    void compactDeltaEmptyShouldNotReadCacheOrRuntimeSnapshot() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 5L);
        when(realtimeChangeTracker.capture()).thenReturn(boundary);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 5L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(5L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.current());
        when(realtimeChangeTracker.findChangedKeys(5L, 5L, null, 20_001)).thenReturn(List.of());

        CompactRealtimeDeltaResponse response = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 5L);

        assertEquals("success", response.getStatus());
        assertEquals("all", response.getScope());
        assertEquals(Boolean.FALSE, response.getResetRequired());
        assertEquals(0, response.getChangedCount());
        assertEquals(List.of(), response.getRows());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(1L, response.getConfigEpoch());
        assertEquals(5L, response.getRevision());
        verify(realtimeChangeTracker, times(1)).capture();
        verify(realtimeChangeTracker, never()).currentRevision();
        verify(realtimeChangeTracker, never()).configEpoch();
        verify(cacheManager, never()).getAll(anyList());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void compactDeltaEmptyShouldResetWhenBoundaryInvalidatesBeforeSuccess() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 10L);
        RealtimeChangeTracker.SnapshotCursor current = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 2L, 11L);
        when(realtimeChangeTracker.capture()).thenReturn(boundary, current);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 10L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(10L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.reset("CONFIG_CHANGED"));
        when(realtimeChangeTracker.findChangedKeys(10L, 10L, null, 20_001)).thenReturn(List.of());

        CompactRealtimeDeltaResponse response = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 10L);

        assertEquals("success", response.getStatus());
        assertEquals(Boolean.TRUE, response.getResetRequired());
        assertEquals("CONFIG_CHANGED", response.getResetReason());
        assertEquals(List.of(), response.getRows());
        assertEquals(0, response.getChangedCount());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(2L, response.getConfigEpoch());
        assertEquals(11L, response.getRevision());
        verify(realtimeChangeTracker, times(2)).capture();
        verify(realtimeChangeTracker, never()).currentRevision();
        verify(realtimeChangeTracker, never()).configEpoch();
        verify(cacheManager, never()).getAll(anyList());
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void compactDeltaSmallShouldReadOnlyChangedCacheKeysOnce() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 4L);
        DataPoint first = point("dev-a", "p1", "temperature");
        DataPoint second = point("dev-a", "p2", "humidity");
        when(realtimeChangeTracker.capture()).thenReturn(boundary);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 1L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(4L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.current());
        when(realtimeChangeTracker.findChangedKeys(1L, 4L, "dev-a", 20_001)).thenReturn(List.of(
                new RealtimeChangeTracker.PointKey("dev-a", "p1"),
                new RealtimeChangeTracker.PointKey("dev-a", "p2")));
        when(configManager.getDataPoints("dev-a")).thenReturn(List.of(first, second));
        when(cacheManager.getAll(anyList())).thenAnswer(invocation -> {
            List<CacheKey> keys = invocation.getArgument(0);
            return Map.of(keys.get(0), "v1", keys.get(1), "v2");
        });

        CompactRealtimeDeltaResponse response = service.getCompactDeviceRealtimeDelta("dev-a", "snapshot-test", 1L, 1L);

        assertEquals("success", response.getStatus());
        assertEquals(Boolean.FALSE, response.getResetRequired());
        assertEquals("device", response.getScope());
        assertEquals("dev-a", response.getDeviceId());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(1L, response.getConfigEpoch());
        assertEquals(4L, response.getRevision());
        assertEquals(2, response.getChangedCount());
        assertEquals(List.of("p1", "p2"), response.getRows().stream().map(CompactRealtimePointPayload::getPointId).toList());
        verify(realtimeChangeTracker, times(1)).capture();
        verify(realtimeChangeTracker, never()).currentRevision();
        verify(realtimeChangeTracker, never()).configEpoch();
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 2
                && "data:dev-a:p1".equals(keys.get(0).getFullKey())
                && "data:dev-a:p2".equals(keys.get(1).getFullKey())));
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void compactDeltaNonEmptyShouldDiscardBuiltRowsWhenBoundaryInvalidatesDuringCacheRead() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 10L);
        RealtimeChangeTracker.SnapshotCursor current = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 2L, 11L);
        DataPoint point = point("dev-a", "p1", "temperature");
        when(realtimeChangeTracker.capture()).thenReturn(boundary, current);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 10L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(10L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.current(), RealtimeChangeTracker.BoundaryValidation.reset("CONFIG_CHANGED"));
        when(realtimeChangeTracker.findChangedKeys(10L, 10L, null, 20_001))
                .thenReturn(List.of(new RealtimeChangeTracker.PointKey("dev-a", "p1")));
        when(configManager.getDataPoints("dev-a")).thenReturn(List.of(point));
        when(cacheManager.getAll(anyList())).thenReturn(Map.of(CacheKey.dataKey("dev-a", "p1"), "v1"));

        CompactRealtimeDeltaResponse response = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 10L);

        assertEquals("success", response.getStatus());
        assertEquals(Boolean.TRUE, response.getResetRequired());
        assertEquals("CONFIG_CHANGED", response.getResetReason());
        assertEquals(List.of(), response.getRows());
        assertEquals(0, response.getChangedCount());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(2L, response.getConfigEpoch());
        assertEquals(11L, response.getRevision());
        verify(realtimeChangeTracker, times(2)).capture();
        verify(realtimeChangeTracker, never()).currentRevision();
        verify(realtimeChangeTracker, never()).configEpoch();
        verify(cacheManager, times(1)).getAll(argThat(keys -> keys.size() == 1
                && "data:dev-a:p1".equals(keys.get(0).getFullKey())));
        verify(pointRuntimeStateService, never()).snapshot(any(), any());
    }

    @Test
    void compactDeltaShouldKeepBoundaryRevisionWhenCurrentRevisionAdvances() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 10L);
        when(realtimeChangeTracker.capture()).thenReturn(boundary);
        when(realtimeChangeTracker.currentRevision()).thenReturn(11L);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 10L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(10L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.current());
        when(realtimeChangeTracker.findChangedKeys(10L, 10L, null, 20_001)).thenReturn(List.of());

        CompactRealtimeDeltaResponse response = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 10L);

        assertEquals("success", response.getStatus());
        assertEquals(Boolean.FALSE, response.getResetRequired());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(1L, response.getConfigEpoch());
        assertEquals(10L, response.getRevision());
        assertEquals(0, response.getChangedCount());
        assertEquals(List.of(), response.getRows());
        verify(realtimeChangeTracker, times(1)).capture();
        verify(realtimeChangeTracker, never()).configEpoch();
        verify(realtimeChangeTracker, never()).currentRevision();
        verify(cacheManager, never()).getAll(anyList());
    }

    @Test
    void compactDeltaShouldResetWhenSnapshotConfigOrRowIdentityMismatch() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 4L);
        when(realtimeChangeTracker.capture()).thenReturn(boundary);
        when(realtimeChangeTracker.validateCursor(boundary, "wrong", 1L, 1L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.reset("SNAPSHOT_MISMATCH", 4L));
        CompactRealtimeDeltaResponse wrongSnapshot = service.getCompactAllRealtimeDelta("wrong", 1L, 1L);
        assertEquals(Boolean.TRUE, wrongSnapshot.getResetRequired());
        assertEquals("SNAPSHOT_MISMATCH", wrongSnapshot.getResetReason());

        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 0L, 1L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.reset("CONFIG_CHANGED", 4L));
        CompactRealtimeDeltaResponse configChanged = service.getCompactAllRealtimeDelta("snapshot-test", 0L, 1L);
        assertEquals("CONFIG_CHANGED", configChanged.getResetReason());

        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 9L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.reset("CURSOR_INVALID", 4L));
        CompactRealtimeDeltaResponse invalidRevision = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 9L);
        assertEquals("CURSOR_INVALID", invalidRevision.getResetReason());

        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 1L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(4L));
        when(realtimeChangeTracker.validateBoundary(boundary))
                .thenReturn(RealtimeChangeTracker.BoundaryValidation.current());
        when(realtimeChangeTracker.findChangedKeys(1L, 4L, null, 20_001))
                .thenReturn(List.of(new RealtimeChangeTracker.PointKey("dev-a", "missing")));
        when(configManager.getDataPoints("dev-a")).thenReturn(List.of(point("dev-a", "p1", "temperature")));
        CompactRealtimeDeltaResponse missingConfig = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 1L);
        assertEquals("ROW_IDENTITY_MISMATCH", missingConfig.getResetReason());
        verify(cacheManager, never()).getAll(anyList());
        verify(realtimeChangeTracker, never()).currentRevision();
    }

    @Test
    void compactDeltaTooLargeShouldResetBeforeBuildingPayload() {
        RealtimeChangeTracker.SnapshotCursor boundary = new RealtimeChangeTracker.SnapshotCursor("snapshot-test", 1L, 30_000L);
        when(realtimeChangeTracker.capture()).thenReturn(boundary);
        when(realtimeChangeTracker.validateCursor(boundary, "snapshot-test", 1L, 1L))
                .thenReturn(RealtimeChangeTracker.CursorValidation.valid(30_000L));
        List<RealtimeChangeTracker.PointKey> tooMany = java.util.stream.IntStream.rangeClosed(1, 20_001)
                .mapToObj(index -> new RealtimeChangeTracker.PointKey("dev-a", "p" + index))
                .toList();
        when(realtimeChangeTracker.findChangedKeys(1L, 30_000L, null, 20_001)).thenReturn(tooMany);

        CompactRealtimeDeltaResponse response = service.getCompactAllRealtimeDelta("snapshot-test", 1L, 1L);

        assertEquals("success", response.getStatus());
        assertEquals(Boolean.TRUE, response.getResetRequired());
        assertEquals("DELTA_TOO_LARGE", response.getResetReason());
        assertEquals("snapshot-test", response.getSnapshotId());
        assertEquals(1L, response.getConfigEpoch());
        assertEquals(30_000L, response.getRevision());
        verify(configManager, never()).getDataPoints(any());
        verify(cacheManager, never()).getAll(anyList());
        verify(realtimeChangeTracker, never()).currentRevision();
    }

    @Test
    void getAllDevicesShouldReturnDeviceCountAndPointCounts() {
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-1", "dev-2"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point("dev-1", "p-1", "temperature")));
        when(configManager.getDataPoints("dev-2")).thenReturn(List.of(
                point("dev-2", "p-1", "temperature"),
                point("dev-2", "p-2", "humidity")));

        DeviceListResponse response = service.getAllDevices();

        assertEquals("success", response.getStatus());
        assertEquals(2, response.getDeviceCount());
        assertEquals(List.of("dev-1", "dev-2"), response.getDevices().stream().map(DeviceBriefResponse::getDeviceId).toList());
        assertEquals(List.of(1, 2), response.getDevices().stream().map(DeviceBriefResponse::getPointCount).toList());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void getDevicePointsShouldBuildPayloadWithoutReadingCacheOrMutatingPoint() {
        DataPoint point = point("dev-1", "p-1", "temperature");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(pointRuntimeStateService.snapshot("dev-1", point))
                .thenReturn(new PointRuntimeStateSnapshot(3000L, 4, "last", 0.8D, 789L));

        DevicePointListResponse response = service.getDevicePoints("dev-1");

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals(1, response.getPointCount());
        PointRealtimePayload payload = response.getPoints().get(0);
        assertEquals("p-1", payload.getPointId());
        assertEquals(3000L, payload.getCurrentCollectionInterval());
        assertEquals(4, payload.getStableCount());
        assertEquals("last", payload.getLastValue());
        assertEquals(0.8D, payload.getChangeRate());
        assertEquals(789L, payload.getLastAdjustTime());
        assertNull(payload.getValue());
        assertEquals(0L, point.getCurrentCollectionInterval());
        assertEquals(0, point.getStableCount());
        assertNull(point.getLastValue());
        assertNotSame(point, payload);
        verify(cacheManager, never()).get(any(CacheKey.class));
        verify(cacheManager, never()).getAll(anyList());
    }

    private DataPoint point(String deviceId, String pointId, String pointCode) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setDeviceName("设备-" + deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setReadWrite("R");
        point.setScalingFactor(1D);
        point.setUnit("℃");
        point.setStatus(1);
        point.setBaseCollectionInterval(1000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(10000L);
        point.setPointChangeThreshold(1D);
        return point;
    }
}
