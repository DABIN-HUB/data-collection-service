package com.wangbin.collector.storage.buffer;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryBatchWriterTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void batchSizeReachedShouldFlushOnce() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
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
        stubDirectSuccess(buffer);
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
        stubDirectSuccess(buffer);
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
        stubDirectSuccess(buffer);
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
        stubDirectSuccess(buffer);
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
        stubDirectSuccess(buffer);
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
    void shutdownDeadlineMustNotLeaveRowsInBuckets() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch firstFlushEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFlush = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            if (calls.incrementAndGet() == 1) {
                firstFlushEntered.countDown();
                assertTrue(releaseFirstFlush.await(1, TimeUnit.SECONDS));
            }
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        HistoryBatchProperties properties = properties(true, 50, 100, 10_000);
        properties.setShutdownFlushTimeoutMs(50L);
        HistoryBatchWriter writer = writer(buffer, properties);
        addRows(writer, "dev-a", 10, 1_000L);
        addRows(writer, "dev-b", 10, 2_000L);
        addRows(writer, "dev-c", 10, 3_000L);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> shutdown = executor.submit(writer::shutdown);
        assertTrue(firstFlushEntered.await(1, TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(80);
        releaseFirstFlush.countDown();
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(30L, metrics.acceptedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(10L, metrics.flushedRows());
        assertEquals(20L, metrics.shutdownDeferredRows());
        assertEquals(20L, metrics.fallbackRedisRows());
    }

    @Test
    void shutdownDeadlineRedisFailureMustExplicitlyAccountNonDurableRows() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.LOCAL_BUFFERED);
        HistoryBatchProperties properties = properties(true, 10, 100, 10_000);
        properties.setShutdownFlushTimeoutMs(0L);
        HistoryBatchWriter writer = writer(buffer, properties);
        addRows(writer, "dev-local", 3, 1_000L);

        writer.shutdown();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(3L, metrics.shutdownDeferredRows());
        assertEquals(3L, metrics.shutdownNonDurableRows());
        assertEquals(3L, metrics.fallbackLocalRows());
        verify(buffer, never()).writeBatchOrBuffer(anyList());
    }

    @Test
    void shutdownBatchFailureShouldContinueFollowingBuckets() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        AtomicInteger calls = new AtomicInteger();
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            if (calls.incrementAndGet() == 1) {
                return new HistoryBatchWriteResult(false, batch.size(), batch.size(), 0, 0, 0);
            }
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10_000));
        addRows(writer, "dev-fail-a", 3, 1_000L);
        addRows(writer, "dev-fail-b", 3, 2_000L);

        writer.shutdown();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(6L, metrics.acceptedRows());
        assertEquals(6L, metrics.flushedRows());
        assertEquals(3L, metrics.fallbackRows());
        assertEquals(3L, metrics.fallbackRedisRows());
        assertEquals(0, metrics.currentBufferedRows());
    }

    @Test
    void concurrentTimerFlushAndShutdownMustNotLoseRows() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
        HistoryBatchWriter writer = writer(buffer, properties(true, 50, 100, 10_000));
        addRows(writer, "dev-race", 40, 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> timer = executor.submit(writer::flushDueBuckets);
        Future<?> shutdown = executor.submit(writer::shutdown);
        timer.get(3, TimeUnit.SECONDS);
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(40L, metrics.acceptedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(40L, metrics.flushedRows() + metrics.fallbackRows());
    }

    @Test
    void acceptDuringShutdownMustNotEnterAbandonedBucket() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));

        writer.shutdown();

        assertThrows(RejectedExecutionException.class,
                () -> writer.accept(request("dev-closing", "p1", 1_000L)));
        assertEquals(0, writer.metrics().currentBufferedRows());
    }

    @Test
    void acceptThatPassedOldClosingCheckMustNotInsertAfterShutdownReturns() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));
        CountDownLatch passedOldClosingCheck = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);
        writer.admissionObserver(new HistoryBatchWriter.AdmissionObserver() {
            @Override
            public void afterInitialClosingCheck(HistoryWriteRequest request) {
                passedOldClosingCheck.countDown();
                try {
                    assertTrue(releaseAccept.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<Boolean> accept = executor.submit(() -> writer.accept(request("dev-race-close", "p1", 1_000L)));
        assertTrue(passedOldClosingCheck.await(1, TimeUnit.SECONDS));
        writer.shutdown();
        assertEquals(0, writer.metrics().currentBufferedRows());
        releaseAccept.countDown();

        ExecutionException exception = assertThrows(ExecutionException.class, () -> accept.get(3, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof RejectedExecutionException);
        assertEquals(0, writer.metrics().currentBufferedRows());
        executor.shutdownNow();
    }

    @Test
    void shutdownAdmissionBarrierMustWaitForInflightAccept() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));
        CountDownLatch enteredAdmission = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        writer.admissionObserver(new HistoryBatchWriter.AdmissionObserver() {
            @Override
            public void beforeBucketOwnershipTransfer(HistoryWriteRequest request) {
                enteredAdmission.countDown();
                try {
                    assertTrue(releaseAdmission.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Boolean> accept = executor.submit(() -> writer.accept(request("dev-admission", "p1", 1_000L)));
        assertTrue(enteredAdmission.await(1, TimeUnit.SECONDS));
        Future<?> shutdown = executor.submit(writer::shutdown);
        assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
        releaseAdmission.countDown();
        assertTrue(accept.get(3, TimeUnit.SECONDS));
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(1L, metrics.acceptedRows());
        assertEquals(1L, metrics.shutdownFlushedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.admissionInFlight());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void timerFlushDetachedBatchShouldRemainOwnedDuringShutdown() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            flushEntered.countDown();
            assertTrue(releaseFlush.await(1, TimeUnit.SECONDS));
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));
        addRows(writer, "dev-timer-owned", 3, 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> timerFlush = executor.submit(writer::flushDueBuckets);
        assertTrue(flushEntered.await(1, TimeUnit.SECONDS));
        Future<?> shutdown = executor.submit(writer::shutdown);
        assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
        releaseFlush.countDown();
        timerFlush.get(3, TimeUnit.SECONDS);
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        verify(buffer, org.mockito.Mockito.times(1)).writeBatchOrBuffer(anyList());
        verify(buffer, never()).deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(3L, metrics.acceptedRows());
        assertEquals(3L, metrics.flushedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void sizeTriggeredFlushAndShutdownMustHaveSingleOwnership() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            flushEntered.countDown();
            assertTrue(releaseFlush.await(1, TimeUnit.SECONDS));
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        HistoryBatchWriter writer = writer(buffer, properties(true, 3, 100, 10));
        writer.accept(request("dev-size-owned", "p1", 1_000L));
        writer.accept(request("dev-size-owned", "p2", 1_001L));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Boolean> sizeTrigger = executor.submit(() -> writer.accept(request("dev-size-owned", "p3", 1_002L)));
        assertTrue(flushEntered.await(1, TimeUnit.SECONDS));
        Future<?> shutdown = executor.submit(writer::shutdown);
        assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
        releaseFlush.countDown();
        assertTrue(sizeTrigger.get(3, TimeUnit.SECONDS));
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        verify(buffer, org.mockito.Mockito.times(1)).writeBatchOrBuffer(anyList());
        verify(buffer, never()).deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(3L, metrics.acceptedRows());
        assertEquals(3L, metrics.flushedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void shutdownReturnMustLeaveNoBucketOrAdmissionInflight() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));
        addRows(writer, "dev-invariant", 4, 1_000L);

        writer.shutdown();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.admissionInFlight());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void maxBufferedRowsConcurrentAdmissionMustRemainBounded() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        HistoryBatchWriter writer = writer(buffer, properties(true, 1_000, 100, 25));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new java.util.ArrayList<>();
        int totalRows = 160;

        for (int thread = 0; thread < 8; thread++) {
            int threadIndex = thread;
            futures.add(executor.submit(() -> {
                for (int index = 0; index < 20; index++) {
                    writer.accept(request("dev-bound", "p" + threadIndex + "-" + index,
                            1_000L + threadIndex * 100L + index));
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(3, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        HistoryBatchMetrics beforeShutdown = writer.metrics();
        assertTrue(beforeShutdown.currentBufferedRows() <= 25);
        assertEquals(totalRows, beforeShutdown.acceptedRows() + beforeShutdown.fallbackRows());
        writer.shutdown();
        HistoryBatchMetrics afterShutdown = writer.metrics();
        assertEquals(0, afterShutdown.currentBufferedRows());
        assertEquals(0, afterShutdown.bucketCount());
        assertEquals(0, afterShutdown.admissionInFlight());
    }

    @Test
    void acceptExceptionMustNotLeakBufferedRows() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter writer = writer(buffer, properties(true, 10, 100, 10));
        writer.admissionObserver(new HistoryBatchWriter.AdmissionObserver() {
            @Override
            public void beforeBucketOwnershipTransfer(HistoryWriteRequest request) {
                throw new IllegalStateException("admission boom");
            }
        });

        assertThrows(IllegalStateException.class, () -> writer.accept(request("dev-exception", "p1", 1_000L)));

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(0L, metrics.acceptedRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.admissionInFlight());
    }

    @Test
    void mixed100RowsShutdownAccountingMustHaveNoUnknownGap() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch firstFlushEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFlush = new CountDownLatch(1);
        AtomicInteger flushCalls = new AtomicInteger();
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            if (flushCalls.incrementAndGet() == 1) {
                firstFlushEntered.countDown();
                assertTrue(releaseFirstFlush.await(1, TimeUnit.SECONDS));
            }
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        HistoryBatchWriter writer = writer(buffer, properties(true, 1_000, 100, 200));
        addRows(writer, "dev-mixed-detached", 20, 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(48);
        Future<?> timerFlush = executor.submit(writer::flushDueBuckets);
        assertTrue(firstFlushEntered.await(1, TimeUnit.SECONDS));
        addRows(writer, "dev-mixed-bucket", 40, 2_000L);
        CountDownLatch inFlightEntered = new CountDownLatch(40);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        writer.admissionObserver(new HistoryBatchWriter.AdmissionObserver() {
            @Override
            public void beforeBucketOwnershipTransfer(HistoryWriteRequest request) {
                if (request.getDeviceId().startsWith("dev-mixed-inflight")) {
                    inFlightEntered.countDown();
                    try {
                        assertTrue(releaseAdmission.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            }
        });
        List<Future<Boolean>> acceptFutures = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            int pointIndex = index;
            acceptFutures.add(executor.submit(() -> writer.accept(request(
                    "dev-mixed-inflight-" + pointIndex, "p" + pointIndex, 3_000L + pointIndex))));
        }
        assertTrue(inFlightEntered.await(1, TimeUnit.SECONDS));
        Future<?> shutdown = executor.submit(writer::shutdown);
        assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
        releaseAdmission.countDown();
        for (Future<Boolean> future : acceptFutures) {
            assertTrue(future.get(3, TimeUnit.SECONDS));
        }
        releaseFirstFlush.countDown();
        timerFlush.get(3, TimeUnit.SECONDS);
        shutdown.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(100L, metrics.acceptedRows());
        assertEquals(100L, metrics.flushedRows() + metrics.fallbackRows());
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.bucketCount());
        assertEquals(0, metrics.admissionInFlight());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void batchFailureWithBufferDisabledMustExplicitlyAccountEveryRow() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.writeBatchOrBuffer(anyList()))
                .thenReturn(new HistoryBatchWriteResult(false, 3, 0, 0, 0, 3));
        HistoryBatchWriter writer = writer(buffer, properties(true, 3, 100, 10));

        addRows(writer, "dev-disabled", 3, 1_000L);

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(3L, metrics.acceptedRows());
        assertEquals(3L, metrics.flushedRows());
        assertEquals(3L, metrics.fallbackRows());
        assertEquals(3L, metrics.fallbackDisabledRows());
        assertEquals(0, metrics.currentBufferedRows());
    }

    @Test
    void slowTdengineMustNotBlockHistoryStageAdmission() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            flushEntered.countDown();
            assertTrue(releaseFlush.await(3, TimeUnit.SECONDS));
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        ThreadPoolExecutor flushExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
        HistoryBatchWriter writer = writer(buffer, properties(true, 3, 100, 10), flushExecutor);
        writer.accept(request("dev-slow", "p1", 1_000L));
        writer.accept(request("dev-slow", "p2", 1_001L));
        ExecutorService historyStageWorker = Executors.newSingleThreadExecutor();

        Future<Boolean> admission = historyStageWorker.submit(() -> writer.accept(request("dev-slow", "p3", 1_002L)));
        assertTrue(flushEntered.await(1, TimeUnit.SECONDS));
        assertTrue(admission.get(200, TimeUnit.MILLISECONDS));
        assertEquals(1, writer.metrics().flushExecutorActiveCurrent());
        assertEquals(0, writer.metrics().currentBufferedRows());

        releaseFlush.countDown();
        historyStageWorker.shutdownNow();
        flushExecutor.shutdown();
        assertTrue(flushExecutor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void fullBatchMustTransferOwnershipToFlushExecutor() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            flushEntered.countDown();
            assertTrue(releaseFlush.await(3, TimeUnit.SECONDS));
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        ThreadPoolExecutor flushExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), flushExecutor);

        writer.accept(request("dev-owned", "p1", 1_000L));
        writer.accept(request("dev-owned", "p2", 1_001L));
        assertTrue(flushEntered.await(1, TimeUnit.SECONDS));
        HistoryBatchMetrics running = writer.metrics();
        assertEquals(1L, running.flushExecutorSubmittedBatches());
        assertEquals(1, running.inFlightFlushes());
        assertEquals(0, running.currentBufferedRows());

        releaseFlush.countDown();
        flushExecutor.shutdown();
        assertTrue(flushExecutor.awaitTermination(3, TimeUnit.SECONDS));
        HistoryBatchMetrics done = writer.metrics();
        assertEquals(1L, done.flushExecutorCompletedBatches());
        assertEquals(2L, done.flushedRows());
        assertEquals(0, done.inFlightFlushes());
    }

    @Test
    void flushExecutorRejectMustFallbackReliably() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("flush executor full");
        };
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), rejectingExecutor);

        addRows(writer, "dev-flush-reject", 2, 1_000L);

        verify(buffer, never()).writeBatchOrBuffer(anyList());
        verify(buffer, org.mockito.Mockito.times(2))
                .deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(1L, metrics.flushExecutorRejectedBatches());
        assertEquals(2L, metrics.fallbackRedisRows());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void flushExecutorRejectMustNotRunTdengineOnHistoryStageThread() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("flush executor full");
        };
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), rejectingExecutor);

        addRows(writer, "dev-no-tdengine-on-stage", 2, 1_000L);

        verify(buffer, never()).writeBatchOrBuffer(anyList());
        assertEquals(1L, writer.metrics().flushExecutorRejectedBatches());
    }

    @Test
    void flushExecutorUnexpectedSubmitFailureMustFallbackReliably() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        Executor failingExecutor = command -> {
            throw new IllegalStateException("flush executor closed");
        };
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), failingExecutor);

        addRows(writer, "dev-flush-submit-failure", 2, 1_000L);

        verify(buffer, never()).writeBatchOrBuffer(anyList());
        verify(buffer, org.mockito.Mockito.times(2))
                .deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(1L, metrics.flushExecutorRejectedBatches());
        assertEquals(2L, metrics.fallbackRedisRows());
        assertEquals(0, metrics.inFlightFlushes());
    }

    @Test
    void batchFailureMustUseExistingRedisFallback() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.writeBatchOrBuffer(anyList()))
                .thenReturn(new HistoryBatchWriteResult(false, 2, 2, 0, 0, 0));
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10));

        addRows(writer, "dev-batch-failure", 2, 1_000L);

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(2L, metrics.fallbackRows());
        assertEquals(2L, metrics.fallbackRedisRows());
        assertEquals(1L, metrics.batchWriteFailure());
    }

    @Test
    void shutdownMustDrainQueuedFlushBatches() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch firstFlushEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            firstFlushEntered.countDown();
            assertTrue(releaseFirstFlush.await(3, TimeUnit.SECONDS));
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        ThreadPoolExecutor flushExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
        HistoryBatchProperties properties = properties(true, 2, 100, 10);
        properties.setShutdownFlushTimeoutMs(50L);
        HistoryBatchWriter writer = writer(buffer, properties, flushExecutor);
        addRows(writer, "dev-queued-a", 2, 1_000L);
        assertTrue(firstFlushEntered.await(1, TimeUnit.SECONDS));
        addRows(writer, "dev-queued-b", 2, 2_000L);

        writer.shutdown();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(0, metrics.currentBufferedRows());
        assertEquals(0, metrics.inFlightFlushes());
        assertEquals(1L, metrics.shutdownQueuedBatches());
        assertTrue(metrics.shutdownDeferredRows() >= 2L);
        assertTrue(metrics.fallbackRedisRows() >= 2L);
        releaseFirstFlush.countDown();
        flushExecutor.shutdown();
        assertTrue(flushExecutor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void shutdownDeadlineMustFallbackRemainingFlushBatches() throws Exception {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            flushEntered.countDown();
            assertTrue(releaseFlush.await(3, TimeUnit.SECONDS));
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.REDIS_BUFFERED);
        ThreadPoolExecutor flushExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());
        HistoryBatchProperties properties = properties(true, 2, 100, 10);
        properties.setShutdownFlushTimeoutMs(50L);
        HistoryBatchWriter writer = writer(buffer, properties, flushExecutor);
        addRows(writer, "dev-timeout", 2, 1_000L);
        assertTrue(flushEntered.await(1, TimeUnit.SECONDS));

        writer.shutdown();

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(0, metrics.inFlightFlushes());
        assertEquals(2L, metrics.shutdownDeferredRows());
        assertEquals(2L, metrics.fallbackRedisRows());
        releaseFlush.countDown();
        flushExecutor.shutdown();
        assertTrue(flushExecutor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void redisFailureDuringFlushRejectMustUseLocalFallback() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.LOCAL_BUFFERED);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("flush executor full");
        };
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), rejectingExecutor);

        addRows(writer, "dev-local-fallback", 2, 1_000L);

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(2L, metrics.fallbackLocalRows());
        assertEquals(0L, metrics.fallbackRedisRows());
    }

    @Test
    void bufferDisabledFlushRejectMustBeExplicitUnreliable() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        when(buffer.deferForRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(HistoryBufferOutcome.DISABLED);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("flush executor full");
        };
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10), rejectingExecutor);

        addRows(writer, "dev-disabled-reject", 2, 1_000L);

        HistoryBatchMetrics metrics = writer.metrics();
        assertEquals(2L, metrics.fallbackDisabledRows());
        assertEquals(2L, metrics.fallbackRows());
    }

    @Test
    void sameDeviceBatchOrderingOrAtLeastOnceSemanticsMustRemainDocumented() {
        HistoryWriteBuffer buffer = mock(HistoryWriteBuffer.class);
        stubDirectSuccess(buffer);
        HistoryBatchWriter writer = writer(buffer, properties(true, 2, 100, 10));

        addRows(writer, "dev-order", 4, 1_000L);

        ArgumentCaptor<List<HistoryWriteRequest>> captor = ArgumentCaptor.captor();
        verify(buffer, org.mockito.Mockito.times(2)).writeBatchOrBuffer(captor.capture());
        assertEquals(List.of(2, 2), captor.getAllValues().stream().map(List::size).toList());
        assertEquals(4L, writer.metrics().flushedRows());
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
        return writer(buffer, properties, DIRECT_EXECUTOR);
    }

    private HistoryBatchWriter writer(HistoryWriteBuffer buffer,
                                      HistoryBatchProperties properties,
                                      Executor flushExecutor) {
        return new HistoryBatchWriter(buffer, properties, flushExecutor);
    }

    private HistoryBatchProperties properties(boolean enabled, int batchSize, long flushIntervalMs, int maxBufferedRows) {
        HistoryBatchProperties properties = new HistoryBatchProperties();
        properties.setEnabled(enabled);
        properties.setBatchSize(batchSize);
        properties.setFlushIntervalMs(flushIntervalMs);
        properties.setMaxBufferedRows(maxBufferedRows);
        return properties;
    }

    private void stubDirectSuccess(HistoryWriteBuffer buffer) {
        when(buffer.writeBatchOrBuffer(anyList())).thenAnswer(invocation -> {
            List<HistoryWriteRequest> batch = invocation.getArgument(0);
            return HistoryBatchWriteResult.directSuccess(batch.size());
        });
    }

    private void addRows(HistoryBatchWriter writer, String deviceId, int rows, long startTs) {
        for (int index = 0; index < rows; index++) {
            writer.accept(request(deviceId, "p" + index, startTs + index));
        }
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
