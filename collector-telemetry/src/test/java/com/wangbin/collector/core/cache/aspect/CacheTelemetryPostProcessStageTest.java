package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        when(cacheManager.put(any(CacheKey.class), any(ProcessResult.class), eq(60_000L))).thenReturn(true);

        stage.process(context("dev-a", "p1", "v1"));

        ArgumentCaptor<ProcessResult> cached = ArgumentCaptor.forClass(ProcessResult.class);
        verify(tracker).record(eq("dev-a"), eq("p1"), cached.capture());
        assertEquals("v1", cached.getValue().getFinalValue());
        assertEquals(1L, (Long) cached.getValue().getMetadata(ProcessResultMetadataKeys.SOURCE_GENERATION));
    }

    @Test
    void failedCacheWriteShouldNotRecordRealtimeTracker() {
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cacheManager, tracker);
        when(cacheManager.put(any(CacheKey.class), any(ProcessResult.class), eq(60_000L))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> stage.process(context("dev-a", "p1", "v1")));

        verify(tracker, never()).record(any(), any(), any());
    }

    @Test
    void trackerFailureShouldNotBreakTelemetryCacheStage() {
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cacheManager, tracker);
        when(cacheManager.put(any(CacheKey.class), any(ProcessResult.class), eq(60_000L))).thenReturn(true);
        doThrow(new IllegalStateException("fingerprint failed")).when(tracker).record(eq("dev-a"), eq("p1"), any());
        doThrow(new IllegalStateException("invalidate failed")).when(tracker).invalidateSnapshot();

        assertDoesNotThrow(() -> stage.process(context("dev-a", "p1", "v1")));
        verify(tracker).invalidateSnapshot();
    }

    private TelemetryPostProcessContext context(String deviceId, String pointId, Object value) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setCacheDuration(60);
        point.setCacheEnabled(1);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long generation = guard.activateNextGeneration(deviceId);
        ProcessResult result = ProcessResult.success(value, value);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "POLLING");
        return new TelemetryPostProcessContext(deviceId, point, result, result, 1000L,
                generation, guard, null, false);
    }
}
