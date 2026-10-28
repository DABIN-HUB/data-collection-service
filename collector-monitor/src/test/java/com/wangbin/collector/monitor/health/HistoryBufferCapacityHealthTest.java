package com.wangbin.collector.monitor.health;

import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryBufferCapacityHealthTest {

    @Test
    void reportsDownWhenRedisPendingAndProcessingReachSharedCapacity() {
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setRedisQueueCapacity(3);
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.metrics()).thenReturn(new HistoryBufferMetrics(1, 2, 0, 0, 10));

        assertEquals(Status.DOWN, new HistoryBufferHealthIndicator(buffer, properties).health().getStatus());
    }

    @Test
    void reportsDownWhenEitherRedisQueueHasNoCapacityLimit() {
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setDeadLetterCapacity(0);
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.metrics()).thenReturn(new HistoryBufferMetrics(0, 0, 0, 0, 10));

        assertEquals(Status.DOWN, new HistoryBufferHealthIndicator(buffer, properties).health().getStatus());
    }
}
