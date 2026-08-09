package com.wangbin.collector.storage.buffer;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 历史正常路径的小批量聚合器；不承担可靠存储，失败统一回落到 HistoryWriteBuffer。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryBatchWriter {

    private static final int METRIC_SAMPLE_LIMIT = 10_000;

    private final HistoryWriteBuffer historyWriteBuffer;
    private final HistoryBatchProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock admissionLock = new ReentrantReadWriteLock();
    private final Object flushMonitor = new Object();
    private final AtomicInteger bufferedRows = new AtomicInteger();
    private final AtomicInteger bufferedRowsPeak = new AtomicInteger();
    private final LongAdder acceptedRows = new LongAdder();
    private final LongAdder flushedBatches = new LongAdder();
    private final LongAdder flushedRows = new LongAdder();
    private final LongAdder batchWriteSuccess = new LongAdder();
    private final LongAdder batchWriteFailure = new LongAdder();
    private final LongAdder fallbackRows = new LongAdder();
    private final LongAdder fallbackRedisRows = new LongAdder();
    private final LongAdder fallbackLocalRows = new LongAdder();
    private final LongAdder fallbackDroppedRows = new LongAdder();
    private final LongAdder fallbackDisabledRows = new LongAdder();
    private final LongAdder shutdownFlushedRows = new LongAdder();
    private final LongAdder shutdownDeferredRows = new LongAdder();
    private final LongAdder shutdownNonDurableRows = new LongAdder();
    private final LongAdder shutdownDroppedRows = new LongAdder();
    private final LongAdder shutdownDisabledRows = new LongAdder();
    private final List<Integer> batchSizeSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> flushLatencyNanos = Collections.synchronizedList(new ArrayList<>());
    private volatile AdmissionObserver admissionObserver = AdmissionObserver.NOOP;
    private int inFlightFlushes;
    private volatile boolean closing;

    /**
     * 接收单条历史写入请求；返回 false 表示批量模式未启用，调用方应走单条路径。
     */
    public boolean accept(HistoryWriteRequest request) {
        if (!properties.isEnabled() || request == null) {
            return false;
        }
        if (closing) {
            throw new RejectedExecutionException("history batch writer is closing");
        }
        AdmissionResult result;
        admissionObserver.afterInitialClosingCheck(request);
        admissionLock.readLock().lock();
        try {
            if (closing) {
                throw new RejectedExecutionException("history batch writer is closing");
            }
            admissionObserver.beforeBucketOwnershipTransfer(request);
            result = admit(request);
        } finally {
            admissionLock.readLock().unlock();
        }
        if (result.bufferFull()) {
            fallback(request, new RejectedExecutionException("history batch buffer full"), false);
            return true;
        }
        flushOwned(result.batchToFlush());
        return true;
    }

    private AdmissionResult admit(HistoryWriteRequest request) {
        int current = bufferedRows.incrementAndGet();
        if (current > maxBufferedRows()) {
            bufferedRows.decrementAndGet();
            return AdmissionResult.capacityRejected();
        }
        acceptedRows.increment();
        updatePeak(current);
        String bucketKey = bucketKey(request);
        AtomicReference<List<HistoryWriteRequest>> batchRef = new AtomicReference<>(List.of());
        buckets.compute(bucketKey, (ignored, existing) -> {
            Bucket bucket = existing == null ? new Bucket() : existing;
            bucket.requests.add(request);
            if (bucket.firstAcceptedAtNanos == 0L) {
                bucket.firstAcceptedAtNanos = System.nanoTime();
            }
            if (bucket.requests.size() >= batchSize()) {
                batchRef.set(drainLocked(bucket, batchSize()));
            }
            return bucket.requests.isEmpty() ? null : bucket;
        });
        List<HistoryWriteRequest> batch = batchRef.get();
        if (!batch.isEmpty()) {
            registerInFlightFlush();
        }
        return AdmissionResult.accepted(batch);
    }

    /**
     * 定期 flush 未满批次，避免低流量设备长期滞留。
     */
    @Scheduled(fixedDelayString = "${telemetry.tdengine.batch.flush-interval-ms:100}")
    public void flushDueBuckets() {
        if (!properties.isEnabled()) {
            return;
        }
        for (List<HistoryWriteRequest> batch : detachDueBatches()) {
            flushOwned(batch);
        }
    }

    /**
     * 停机时尽力 flush 内存批次，失败仍进入既有 Redis/local fallback。
     */
    @PreDestroy
    public void shutdown() {
        closing = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getShutdownFlushTimeoutMs());
        awaitAdmissionBarrier();
        List<List<HistoryWriteRequest>> batches = drainAllBatches();
        int index = 0;
        while (index < batches.size()) {
            List<HistoryWriteRequest> batch = batches.get(index);
            if (System.nanoTime() >= deadline) {
                break;
            }
            shutdownFlushedRows.add(batch.size());
            flush(batch);
            index++;
        }
        for (; index < batches.size(); index++) {
            deferShutdownBatch(batches.get(index), new RejectedExecutionException("history batch shutdown deadline exceeded"));
        }
        waitForInFlightFlushes();
    }

    /**
     * 返回批量写入内部指标快照。
     */
    public HistoryBatchMetrics metrics() {
        return new HistoryBatchMetrics(
                acceptedRows.sum(),
                flushedBatches.sum(),
                flushedRows.sum(),
                batchWriteSuccess.sum(),
                batchWriteFailure.sum(),
                fallbackRows.sum(),
                bufferedRows.get(),
                bufferedRowsPeak.get(),
                averageBatchSize(),
                percentileInt(batchSizeSamples, 0.50D),
                percentileInt(batchSizeSamples, 0.95D),
                maxInt(batchSizeSamples),
                percentileMillis(flushLatencyNanos, 0.50D),
                percentileMillis(flushLatencyNanos, 0.95D),
                percentileMillis(flushLatencyNanos, 0.99D),
                oldestBufferedAgeMs(),
                shutdownFlushedRows.sum(),
                fallbackRedisRows.sum(),
                fallbackLocalRows.sum(),
                fallbackDroppedRows.sum(),
                fallbackDisabledRows.sum(),
                shutdownDeferredRows.sum(),
                shutdownNonDurableRows.sum(),
                shutdownDroppedRows.sum(),
                shutdownDisabledRows.sum(),
                buckets.size(),
                admissionLock.getReadLockCount(),
                currentInFlightFlushes());
    }

    private List<List<HistoryWriteRequest>> detachDueBatches() {
        List<List<HistoryWriteRequest>> batches = new ArrayList<>();
        admissionLock.readLock().lock();
        try {
            if (closing) {
                return List.of();
            }
            for (String bucketKey : List.copyOf(buckets.keySet())) {
                AtomicReference<List<HistoryWriteRequest>> batchRef = new AtomicReference<>(List.of());
                buckets.computeIfPresent(bucketKey, (ignored, bucket) -> {
                    List<HistoryWriteRequest> batch = drainLocked(bucket, batchSize());
                    batchRef.set(batch);
                    return bucket.requests.isEmpty() ? null : bucket;
                });
                List<HistoryWriteRequest> batch = batchRef.get();
                if (!batch.isEmpty()) {
                    registerInFlightFlush();
                    batches.add(batch);
                }
            }
            return batches;
        } finally {
            admissionLock.readLock().unlock();
        }
    }

    private void awaitAdmissionBarrier() {
        admissionLock.writeLock().lock();
        try {
            // 写锁成功表示已经进入准入临界区的接收/定时脱离操作已完成所有权转移。
        } finally {
            admissionLock.writeLock().unlock();
        }
    }

    private void flushOwned(List<HistoryWriteRequest> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            flush(batch);
        } finally {
            completeInFlightFlush();
        }
    }

    private void flush(List<HistoryWriteRequest> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            HistoryBatchWriteResult result = historyWriteBuffer.writeBatchOrBuffer(batch);
            if (result.directSuccess()) {
                batchWriteSuccess.increment();
            } else {
                batchWriteFailure.increment();
                recordBatchFallback(result);
            }
        } catch (RuntimeException exception) {
            batchWriteFailure.increment();
            for (HistoryWriteRequest request : batch) {
                fallback(request, exception, false);
            }
        } finally {
            flushedBatches.increment();
            flushedRows.add(batch.size());
            recordBatchSize(batch.size());
            recordFlushLatency(System.nanoTime() - startedAt);
        }
    }

    private void fallback(HistoryWriteRequest request, RuntimeException exception, boolean shutdownFallback) {
        fallbackRows.increment();
        HistoryBufferOutcome outcome;
        try {
            outcome = historyWriteBuffer.deferForRetry(request, exception);
        } catch (RuntimeException fallbackException) {
            log.error("历史批量写入 fallback 异常，数据明确丢弃，设备={}，点位={}",
                    request != null ? request.getDeviceId() : null,
                    pointId(request),
                    fallbackException);
            outcome = HistoryBufferOutcome.DROPPED;
        }
        recordFallbackOutcome(outcome, shutdownFallback);
    }

    private void recordBatchFallback(HistoryBatchWriteResult result) {
        fallbackRows.add(result.fallbackRows());
        fallbackRedisRows.add(result.redisBufferedRows());
        fallbackLocalRows.add(result.localBufferedRows());
        fallbackDroppedRows.add(result.droppedRows());
        fallbackDisabledRows.add(result.disabledRows());
    }

    private void recordFallbackOutcome(HistoryBufferOutcome outcome, boolean shutdownFallback) {
        if (outcome == null) {
            outcome = HistoryBufferOutcome.DROPPED;
        }
        switch (outcome) {
            case REDIS_BUFFERED -> fallbackRedisRows.increment();
            case LOCAL_BUFFERED -> fallbackLocalRows.increment();
            case DROPPED -> fallbackDroppedRows.increment();
            case DISABLED -> fallbackDisabledRows.increment();
        }
        if (shutdownFallback) {
            shutdownDeferredRows.increment();
            if (outcome == HistoryBufferOutcome.LOCAL_BUFFERED) {
                shutdownNonDurableRows.increment();
            } else if (outcome == HistoryBufferOutcome.DROPPED) {
                shutdownDroppedRows.increment();
            } else if (outcome == HistoryBufferOutcome.DISABLED) {
                shutdownDisabledRows.increment();
            }
        }
    }

    private void deferShutdownBatch(List<HistoryWriteRequest> batch, RuntimeException exception) {
        for (HistoryWriteRequest request : batch) {
            fallback(request, exception, true);
        }
    }

    private void registerInFlightFlush() {
        synchronized (flushMonitor) {
            inFlightFlushes++;
        }
    }

    private void completeInFlightFlush() {
        synchronized (flushMonitor) {
            inFlightFlushes--;
            if (inFlightFlushes <= 0) {
                flushMonitor.notifyAll();
            }
        }
    }

    private void waitForInFlightFlushes() {
        synchronized (flushMonitor) {
            while (inFlightFlushes > 0) {
                try {
                    flushMonitor.wait(100L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.warn("等待历史批量在途 flush 完成时被中断，仍由在途 flush 线程负责已脱离 bucket 的数据");
                    return;
                }
            }
        }
    }

    private int currentInFlightFlushes() {
        synchronized (flushMonitor) {
            return inFlightFlushes;
        }
    }

    private List<List<HistoryWriteRequest>> drainAllBatches() {
        List<List<HistoryWriteRequest>> batches = new ArrayList<>();
        for (String bucketKey : List.copyOf(buckets.keySet())) {
            AtomicReference<List<HistoryWriteRequest>> drainedRef = new AtomicReference<>(List.of());
            buckets.computeIfPresent(bucketKey, (ignored, bucket) -> {
                drainedRef.set(drainAllLocked(bucket));
                return null;
            });
            for (List<HistoryWriteRequest> batch : partition(drainedRef.get(), batchSize())) {
                if (!batch.isEmpty()) {
                    batches.add(batch);
                }
            }
        }
        return batches;
    }

    private List<List<HistoryWriteRequest>> partition(List<HistoryWriteRequest> rows, int size) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<List<HistoryWriteRequest>> batches = new ArrayList<>();
        for (int index = 0; index < rows.size(); index += size) {
            batches.add(new ArrayList<>(rows.subList(index, Math.min(index + size, rows.size()))));
        }
        return batches;
    }

    private List<HistoryWriteRequest> drainLocked(Bucket bucket, int maxRows) {
        if (bucket.requests.isEmpty()) {
            return List.of();
        }
        int count = Math.min(maxRows, bucket.requests.size());
        List<HistoryWriteRequest> batch = new ArrayList<>(bucket.requests.subList(0, count));
        bucket.requests.subList(0, count).clear();
        bufferedRows.addAndGet(-count);
        if (bucket.requests.isEmpty()) {
            bucket.firstAcceptedAtNanos = 0L;
        } else {
            bucket.firstAcceptedAtNanos = System.nanoTime();
        }
        return batch;
    }

    private List<HistoryWriteRequest> drainAllLocked(Bucket bucket) {
        if (bucket.requests.isEmpty()) {
            return List.of();
        }
        List<HistoryWriteRequest> batch = new ArrayList<>(bucket.requests);
        bucket.requests.clear();
        bufferedRows.addAndGet(-batch.size());
        bucket.firstAcceptedAtNanos = 0L;
        return batch;
    }

    private String pointId(HistoryWriteRequest request) {
        return request == null || request.getPoint() == null ? null : request.getPoint().getPointId();
    }

    private String bucketKey(HistoryWriteRequest request) {
        return String.valueOf(request.getDeviceId());
    }

    private int batchSize() {
        return Math.max(1, properties.getBatchSize());
    }

    private int maxBufferedRows() {
        return Math.max(1, properties.getMaxBufferedRows());
    }

    private void updatePeak(int current) {
        bufferedRowsPeak.accumulateAndGet(current, Math::max);
    }

    private void recordBatchSize(int size) {
        synchronized (batchSizeSamples) {
            if (batchSizeSamples.size() < METRIC_SAMPLE_LIMIT) {
                batchSizeSamples.add(size);
            }
        }
    }

    private void recordFlushLatency(long nanos) {
        synchronized (flushLatencyNanos) {
            if (flushLatencyNanos.size() < METRIC_SAMPLE_LIMIT) {
                flushLatencyNanos.add(nanos);
            }
        }
    }

    private double averageBatchSize() {
        long batches = flushedBatches.sum();
        return batches <= 0L ? 0D : (double) flushedRows.sum() / batches;
    }

    private int percentileInt(List<Integer> values, double percentile) {
        List<Integer> sorted = snapshot(values);
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.max(0, index));
    }

    private int maxInt(List<Integer> values) {
        List<Integer> sorted = snapshot(values);
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
    }

    private double percentileMillis(List<Long> values, double percentile) {
        List<Long> sorted = snapshot(values);
        if (sorted.isEmpty()) {
            return 0D;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.max(0, index)) / 1_000_000.0D;
    }

    private <T extends Comparable<? super T>> List<T> snapshot(List<T> values) {
        synchronized (values) {
            return values.stream().sorted().toList();
        }
    }

    private long oldestBufferedAgeMs() {
        long now = System.nanoTime();
        long oldest = 0L;
        for (Bucket bucket : buckets.values()) {
            synchronized (bucket) {
                if (!bucket.requests.isEmpty() && bucket.firstAcceptedAtNanos > 0L) {
                    long age = TimeUnit.NANOSECONDS.toMillis(now - bucket.firstAcceptedAtNanos);
                    oldest = Math.max(oldest, age);
                }
            }
        }
        return oldest;
    }

    private static final class Bucket {
        private final List<HistoryWriteRequest> requests = new ArrayList<>();
        private long firstAcceptedAtNanos;
    }

    private record AdmissionResult(List<HistoryWriteRequest> batchToFlush, boolean bufferFull) {

        private static AdmissionResult accepted(List<HistoryWriteRequest> batchToFlush) {
            return new AdmissionResult(batchToFlush, false);
        }

        private static AdmissionResult capacityRejected() {
            return new AdmissionResult(List.of(), true);
        }
    }

    void admissionObserver(AdmissionObserver admissionObserver) {
        this.admissionObserver = admissionObserver == null ? AdmissionObserver.NOOP : admissionObserver;
    }

    interface AdmissionObserver {
        AdmissionObserver NOOP = new AdmissionObserver() {
        };

        default void afterInitialClosingCheck(HistoryWriteRequest request) {
        }

        default void beforeBucketOwnershipTransfer(HistoryWriteRequest request) {
        }
    }
}
