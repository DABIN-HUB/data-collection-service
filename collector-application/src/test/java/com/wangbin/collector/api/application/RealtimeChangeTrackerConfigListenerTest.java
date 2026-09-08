package com.wangbin.collector.api.application;

import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RealtimeChangeTrackerConfigListenerTest {

    @Test
    void configUpdateEventShouldInvalidateRealtimeTrackerConfiguration() {
        RealtimeChangeTracker tracker = mock(RealtimeChangeTracker.class);
        RealtimeChangeTrackerConfigListener listener = new RealtimeChangeTrackerConfigListener(tracker);

        listener.handleConfigUpdate(ConfigUpdateEvent.createDeviceUpdateEvent("dev-a", false));

        verify(tracker).invalidateConfiguration();
    }
}
