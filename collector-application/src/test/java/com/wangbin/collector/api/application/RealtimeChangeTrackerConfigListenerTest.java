package com.wangbin.collector.api.application;

import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RealtimeChangeTrackerConfigListenerTest {

    @Test
    void configUpdateEventShouldInvalidateRealtimeTrackerConfiguration() {
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        MultiLevelCacheManager cache = mock(MultiLevelCacheManager.class);
        RealtimeChangeTrackerConfigListener listener = new RealtimeChangeTrackerConfigListener(
                tracker, cache, new CollectionTaskGuard());

        listener.handleConfigUpdate(ConfigUpdateEvent.createDeviceUpdateEvent("dev-a", false));

        verify(tracker).invalidateConfiguration();
        verifyNoInteractions(cache);
    }

    @Test
    void lateRetirementShouldRemoveOnlyOldConfigurationValues() {
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        MultiLevelCacheManager cache = mock(MultiLevelCacheManager.class);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        RealtimeChangeTrackerConfigListener listener = new RealtimeChangeTrackerConfigListener(tracker, cache, guard);
        ProcessResult old = result(guard.runtimeId(), 10L);
        ProcessResult rebuilt = result(guard.runtimeId(), 12L);
        ProcessResult previousProcess = result("previous-runtime", 100L);
        when(cache.deleteIf(eq(CacheKey.dataKey("dev-a", "p1")), any())).thenAnswer(invocation -> {
            Predicate<Object> predicate = invocation.getArgument(1);
            assertTrue(predicate.test(old));
            assertFalse(predicate.test(rebuilt));
            assertTrue(predicate.test(previousProcess));
            assertTrue(predicate.test("legacy-value"));
            return true;
        });

        listener.handleConfigUpdate(ConfigUpdateEvent.builder().deviceId("dev-a")
                .configVersion(11L).retiredPointIds(Set.of("p1")).build());

        verify(cache).deleteIf(eq(CacheKey.dataKey("dev-a", "p1")), any());
        verify(tracker).invalidateConfiguration();
    }

    private ProcessResult result(String runtimeId, long version) {
        ProcessResult result = ProcessResult.success(1, 1);
        result.addMetadata(ProcessResultMetadataKeys.RUNTIME_ID, runtimeId);
        result.addMetadata(ProcessResultMetadataKeys.CONFIG_VERSION, version);
        return result;
    }
}
