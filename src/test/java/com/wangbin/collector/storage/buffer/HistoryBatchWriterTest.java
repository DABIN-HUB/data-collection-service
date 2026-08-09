package com.wangbin.collector.storage.buffer;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryBatchWriterTest {

    @Test
    void batchSizeReachedShouldFlushOnce() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 3, 100, 10));

        assertTrue(writer.accept(request("dev-1", "p1", 1_000L)));
        assertTrue(writer.accept(request("dev-1", "p2", 1_001L)));
        assertTrue(writer.accept(request("dev-1", "p3", 1_002L)));

        ArgumentCaptor<List<HistoryWriteRequest>> captor = ArgumentCaptor.captor();
        verify(buffer).writeBatchOrBuffer(captor.capture());
        assertEquals(3, captor.getValue().size());
        assertEquals(3L, writer.metrics().flushedRows());
        assertEquals(0, writer.metrics().currentBufferedRows());
    }

    @Test
    void flushIntervalShouldFlushPartialBatch() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));

        writer.accept(request("dev-1", "p1", 1_000L));
        writer.accept(request("dev-1", "p2", 1_001L));
        writer.flushDueBuckets();

        ArgumentCaptor<List<HistoryWriteRequest>> captor = ArgumentCaptor.captor();
        verify(buffer).writeBatchOrBuffer(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(0, writer.metrics().currentBufferedRows());
    }

    @Test
    void concurrentSizeAndTimerFlushMustNotLoseRows() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 3, 100, 10));

        writer.accept(request("dev-1", "p1", 1_000L));
        writer.accept(request("dev-1", "p2", 1_001L));
        writer.accept(request("dev-1", "p3", 1_002L));
        writer.flushDueBuckets();

        assertEquals(3L, writer.metrics().acceptedRows());
        assertEquals(3L, writer.metrics().flushedRows());
        assertEquals(0, writer.metrics().currentBufferedRows());
    }

    @Test
    void concurrentAcceptAndTimerFlushMustNotOrphanRows() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.writeBatchOrBuffer(anyList())).thenReturn(true);
        HistoryBatchWriter writer = writer(buffer, properties(true, 25, 100, 10_000));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int rows = 2_000;

        Future<?> producer = executor.submit(() -> {
            for (int i = 0; i < rows; i++) {
                writer.accept(request("dev-1", "p" + i, 1_000L + i));
            }
        });
        Future<?> flusher = executor.submit(() -> {
            while (!producer.isDone()) {
                writer.flushDueBuckets();
            }
        });
        producer.get(10, TimeUnit.SECONDS);
        flusher.get(10, TimeUnit.SECONDS);
        for (int i = 0; i < 100 && writer.metrics().currentBufferedRows() > 0; i++) {
            writer.flushDueBuckets();
        }
        executor.shutdownNow();

        assertEquals(rows, writer.metrics().acceptedRows());
        assertEquals(rows, writer.metrics().flushedRows());
        assertEquals(0, writer.metrics().currentBufferedRows());
    }

    @Test
    void differentDevicesShouldBeGroupedByDeviceBucket() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10));

        writer.accept(request("dev-a", "p1", 1_000L));
        writer.accept(request("dev-b", "p1", 1_000L));
        writer.accept(request("dev-a", "p2", 1_001L));
        writer.accept(request("dev-b", "p2", 1_001L));

        ArgumentCaptor<List<HistoryWriteRequest>> captor = ArgumentCaptor.captor();
        verify(buffer, org.mockito.Mockito.times(2)).writeBatchOrBuffer(captor.capture());
        assertEquals(List.of(2, 2), captor.getAllValues().stream().map(List::size).toList());
    }

    @Test
    void maxBufferedRowsShouldUseExistingFallbackWithoutUnboundedGrowth() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 2));

        writer.accept(request("dev-1", "p1", 1_000L));
        writer.accept(request("dev-1", "p2", 1_001L));
        writer.accept(request("dev-1", "p3", 1_002L));

        verify(buffer).deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(2, writer.metrics().currentBufferedRows());
        assertEquals(1L, writer.metrics().fallbackRows());
    }

    @Test
    void shutdownShouldFlushRemainingBatch() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));

        writer.accept(request("dev-1", "p1", 1_000L));
        writer.accept(request("dev-1", "p2", 1_001L));
        writer.shutdown();

        ArgumentCaptor<List<HistoryWriteRequest>> captor = ArgumentCaptor.captor();
        verify(buffer).writeBatchOrBuffer(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(2L, writer.metrics().shutdownFlushedRows());
    }

    @Test
    void batchDisabledShouldReturnFalseAndAvoidBuffering() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(false, 10, 100, 10));

        assertFalse(writer.accept(request("dev-1", "p1", 1_000L)));

        verify(buffer, never()).writeBatchOrBuffer(anyList());
        assertEquals(0L, writer.metrics().acceptedRows());
    }

    private HistoryBatchWriter writer(HistoryWriteBuffer buffer, HistoryBatchProperties properties) {
        return new HistoryBatchWriter(buffer, properties);
    }

    private HistoryBatchProperties properties(boolean enabled, int batchSize, long flushIntervalMs, int maxBufferedRows) {
        HistoryBatchProperties properties = new HistoryBatchProperties();
        properties.setEnabled(enabled);
        properties.setBatchSize(batchSize);
        properties.setFlushIntervalMs(flushIntervalMs);
        properties.setMaxBufferedRows(maxBufferedRows);
        return properties;
    }

    private HistoryWriteRequest request(String deviceId, String pointId, long eventTs) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return new HistoryWriteRequest(deviceId, "MODBUS_TCP", point, ProcessResult.success(1, 1), eventTs);
    }
}
