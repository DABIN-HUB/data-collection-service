package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheTelemetryPostProcessStageTest {

    @Test
    void successfulCacheWriteShouldRecordRealtimeTracker() {
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cacheManager, tracker);
        when(cacheManager.put(any(CacheKey.class), eq("v1"), eq(60_000L))).thenReturn(true);

        stage.process(context("dev-a", "p1", "v1"));

        verify(tracker).record("dev-a", "p1", "v1");
    }

    @Test
    void failedCacheWriteShouldNotRecordRealtimeTracker() {
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cacheManager, tracker);
        when(cacheManager.put(any(CacheKey.class), eq("v1"), eq(60_000L))).thenReturn(false);

        stage.process(context("dev-a", "p1", "v1"));

        verify(tracker, never()).record(any(), any(), any());
    }

    @Test
    void trackerFailureShouldNotBreakTelemetryCacheStage() {
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cacheManager, tracker);
        when(cacheManager.put(any(CacheKey.class), eq("v1"), eq(60_000L))).thenReturn(true);
        doThrow(new IllegalStateException("fingerprint failed")).when(tracker).record("dev-a", "p1", "v1");
        doThrow(new IllegalStateException("invalidate failed")).when(tracker).invalidateSnapshot();

        assertDoesNotThrow(() -> stage.process(context("dev-a", "p1", "v1")));
        verify(tracker).invalidateSnapshot();
    }

    private TelemetryPostProcessContext context(String deviceId, String pointId, Object value) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setCacheDuration(60);
        point.setCacheEnabled(1);
        return new TelemetryPostProcessContext(deviceId, point, null, value, 1000L, 1L);
    }
}
