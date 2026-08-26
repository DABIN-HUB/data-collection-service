package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.DeviceBriefResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeDataQueryApplicationServiceTest {

    private MultiLevelCacheManager cacheManager;
    private ConfigManager configManager;
    private PointRuntimeStateService pointRuntimeStateService;
    private RealtimeDataQueryApplicationService service;

    @BeforeEach
    void setUp() {
        cacheManager = mock(MultiLevelCacheManager.class);
        configManager = mock(ConfigManager.class);
        pointRuntimeStateService = mock(PointRuntimeStateService.class);
        service = new RealtimeDataQueryApplicationService(cacheManager, configManager, pointRuntimeStateService);
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
        point.setBaseCollectionInterval(1000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(10000L);
        point.setPointChangeThreshold(1D);
        return point;
    }
}
