package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AdaptiveResetResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeDataApplicationServiceTest {

    private RealtimeDataQueryApplicationService realtimeDataQueryApplicationService;
    private DataHistoryApplicationService dataHistoryApplicationService;
    private ConfigManager configManager;
    private PointRuntimeStateService pointRuntimeStateService;
    private RealtimeDataApplicationService service;

    @BeforeEach
    void setUp() {
        realtimeDataQueryApplicationService = mock(RealtimeDataQueryApplicationService.class);
        dataHistoryApplicationService = mock(DataHistoryApplicationService.class);
        configManager = mock(ConfigManager.class);
        pointRuntimeStateService = mock(PointRuntimeStateService.class);
        service = new RealtimeDataApplicationService(
                realtimeDataQueryApplicationService,
                dataHistoryApplicationService,
                configManager,
                pointRuntimeStateService);
    }

    @Test
    void resetAdaptiveConfigShouldResetEveryConfiguredPoint() {
        DataPoint first = point("dev-1", "p-1");
        DataPoint second = point("dev-1", "p-2");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(first, second));

        AdaptiveResetResponse response = service.resetAdaptiveConfig("dev-1");

        assertEquals(200, response.getCode());
        assertEquals("重置成功", response.getMessage());
        verify(pointRuntimeStateService).reset("dev-1", first);
        verify(pointRuntimeStateService).reset("dev-1", second);
    }

    @Test
    void resetAdaptiveConfigShouldKeepExistingErrorResponseWhenConfigFails() {
        when(configManager.getDataPoints("dev-1")).thenThrow(new IllegalStateException("config down"));

        AdaptiveResetResponse response = service.resetAdaptiveConfig("dev-1");

        assertEquals(400, response.getCode());
        assertEquals("重置失败", response.getMessage());
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setBaseCollectionInterval(1000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(10000L);
        point.setPointChangeThreshold(1D);
        return point;
    }
}
