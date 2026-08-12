package com.wangbin.collector.storage.buffer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import com.wangbin.collector.storage.config.TdengineProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * 历史正常路径的小批量聚合器；可靠存储仍由 HistoryWriteBuffer 统一负责。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryBatchWriter {

    private static final int METRIC_SAMPLE_LIMIT = 10_000;

    private final HistoryWriteBuffer historyWriteBuffer;
    private final HistoryBatchProperties properties;
    private final TdengineProperties tdengineProperties;
    private final Executor historyBatchFlushExecutor;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<Long, BatchFlushTask> ownedFlushTasks = new ConcurrentHashMap<>();
    private final Map<String, SubTableFlushState> subTableFlushStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> activeWritesBySubTable = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> subTableWriteLatencyNanos = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock admissionLock = new ReentrantReadWriteLock();
    private final Object flushMonitor = new Object();
    private final AtomicInteger bufferedRows = new AtomicInteger();
    private final AtomicInteger bufferedRowsPeak = new AtomicInteger();
    private final AtomicLong flushTaskSequence = new AtomicLong();
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
    private final LongAdder flushExecutorSubmittedBatches = new LongAdder();
    private final LongAdder flushExecutorCompletedBatches = new LongAdder();
    private final LongAdder flushExecutorRejectedBatches = new LongAdder();
    private final LongAdder sizeFlushBatches = new LongAdder();
    private final LongAdder timerFlushBatches = new LongAdder();
    private final LongAdder sizeFlushRows = new LongAdder();
    private final LongAdder timerFlushRows = new LongAdder();
    private final LongAdder tdengineWriteRequests = new LongAdder();
    private final LongAdder tdengineWriteRows = new LongAdder();
    private final LongAdder multiTableWriteRequests = new LongAdder();
    private final LongAdder multiTableWriteRows = new LongAdder();
    private final LongAdder multiTableAggregatedBatches = new LongAdder();
    private final List<Integer> rowsPerTdengineRequestSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> tablesPerTdengineRequestSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> dbQueueWaitNanos = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> dbExecuteLatencyNanos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flushExecutorQueueCurrent = new AtomicInteger();
    private final AtomicInteger flushExecutorQueuePeak = new AtomicInteger();
    private final AtomicInteger flushExecutorActiveCurrent = new AtomicInteger();
    private final AtomicInteger flushExecutorActivePeak = new AtomicInteger();
    private final AtomicInteger maxConcurrentWritesSameSubTable = new AtomicInteger();
    private final LongAdder shutdownQueuedBatches = new LongAdder();
    private final LongAdder sameSubTableConcurrentWriteCount = new LongAdder();
    private final List<Integer> batchSizeSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> sizeBatchSizeSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> timerBatchSizeSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> flushLatencyNanos = Collections.synchronizedList(new ArrayList<>());
    private final long createdAtNanos = System.nanoTime();
    private volatile AdmissionObserver admissionObserver = AdmissionObserver.NOOP;
    private volatile LongSupplier nanoTimeSupplier = System::nanoTime;
    private int inFlightFlushes;
    private volatile boolean closing;

    /**
     * 创建历史批量写入器；flush executor 只执行 TDengine batch I/O，不参与 point admission。
     */
    @Autowired
    public HistoryBatchWriter(HistoryWriteBuffer historyWriteBuffer,
                              HistoryBatchProperties properties,
                              TdengineProperties tdengineProperties,
                              @Qualifier(HistoryBatchFlushExecutorConfig.HISTORY_BATCH_FLUSH_EXECUTOR)
                              Executor historyBatchFlushExecutor) {
        this.historyWriteBuffer = historyWriteBuffer;
        this.properties = properties;
        this.tdengineProperties = tdengineProperties;
        this.historyBatchFlushExecutor = historyBatchFlushExecutor;
    }

    HistoryBatchWriter(HistoryWriteBuffer historyWriteBuffer,
                       HistoryBatchProperties properties,
                       Executor historyBatchFlushExecutor) {
        this(historyWriteBuffer, properties, new TdengineProperties(), historyBatchFlushExecutor);
    }

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
        submitOwnedFlush(result.batchToFlush(), false, FlushTrigger.SIZE);
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
                bucket.firstAcceptedAtNanos = currentNanoTime();
            }
            if (bucket.requests.size() >= batchSize()) {
                batchRef.set(drainLocked(bucket, batchSize()));
            }
            return bucket.requests.isEmpty() ? null : bucket;
        });
        return AdmissionResult.accepted(batchRef.get());
    }

    /**
     * 定期 flush 未满批次，避免低流量设备长期滞留。
     */
    @Scheduled(fixedDelayString = "${telemetry.tdengine.batch.flush-scan-interval-ms:100}")
    public void flushDueBuckets() {
        if (!properties.isEnabled()) {
            return;
        }
        for (List<HistoryWriteRequest> batch : detachDueBatches()) {
            submitOwnedFlush(batch, false, FlushTrigger.TIMER);
        }
    }

    /**
     * 停机时关闭 admission，已脱离 bucket 的 batch 要么完成 flush，要么进入既有 fallback。
     */
    @PreDestroy
    public void shutdown() {
        closing = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getShutdownFlushTimeoutMs());
        awaitAdmissionBarrier();
        List<List<HistoryWriteRequest>> batches = drainAllBatches();
        int index = 0;
        while (index < batches.size()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            submitOwnedFlush(batches.get(index), true, FlushTrigger.SHUTDOWN);
            index++;
        }
        for (; index < batches.size(); index++) {
            deferShutdownBatch(batches.get(index),
                    new RejectedExecutionException("history batch shutdown deadline exceeded"));
        }
        waitForInFlightFlushes(deadline);
        fallbackOutstandingFlushTasks(
                new RejectedExecutionException("history batch shutdown deadline exceeded"));
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
                flushExecutorSubmittedBatches.sum(),
                flushExecutorCompletedBatches.sum(),
                flushExecutorRejectedBatches.sum(),
                flushExecutorQueueCurrent.get(),
                flushExecutorQueuePeak.get(),
                flushExecutorActiveCurrent.get(),
                flushExecutorActivePeak.get(),
                shutdownQueuedBatches.sum(),
                buckets.size(),
                admissionLock.getReadLockCount(),
                currentInFlightFlushes(),
                sizeFlushBatches.sum(),
                timerFlushBatches.sum(),
                sizeFlushRows.sum(),
                timerFlushRows.sum(),
                averageBatchSize(sizeFlushRows.sum(), sizeFlushBatches.sum()),
                percentileInt(sizeBatchSizeSamples, 0.50D),
                percentileInt(sizeBatchSizeSamples, 0.95D),
                maxInt(sizeBatchSizeSamples),
                averageBatchSize(timerFlushRows.sum(), timerFlushBatches.sum()),
                percentileInt(timerBatchSizeSamples, 0.50D),
                percentileInt(timerBatchSizeSamples, 0.95D),
                maxInt(timerBatchSizeSamples),
                tdengineBatchCallsPerSecond(),
                flushExecutorServiceRatePerSecond(),
                flushExecutorQueueUtilization(),
                tdengineWriteRequests.sum(),
                tdengineWriteRows.sum(),
                ratePerSecond(tdengineWriteRequests.sum()),
                averageBatchSize(tdengineWriteRows.sum(), tdengineWriteRequests.sum()),
                percentileInt(rowsPerTdengineRequestSamples, 0.95D),
                maxInt(rowsPerTdengineRequestSamples),
                averageBatchSize(sumIntSamples(tablesPerTdengineRequestSamples), tdengineWriteRequests.sum()),
                percentileInt(tablesPerTdengineRequestSamples, 0.95D),
                maxInt(tablesPerTdengineRequestSamples),
                multiTableWriteRequests.sum(),
                multiTableWriteRows.sum(),
                multiTableAggregatedBatches.sum(),
                activeWritesSnapshot(),
                maxConcurrentWritesSameSubTable.get(),
                sameSubTableConcurrentWriteCount.sum(),
                percentileMillis(dbQueueWaitNanos, 0.50D),
                percentileMillis(dbQueueWaitNanos, 0.95D),
                percentileMillis(dbQueueWaitNanos, 0.99D),
                percentileMillis(dbExecuteLatencyNanos, 0.50D),
                percentileMillis(dbExecuteLatencyNanos, 0.95D),
                percentileMillis(dbExecuteLatencyNanos, 0.99D),
                subTableLatencyP95Snapshot());
    }

    private List<List<HistoryWriteRequest>> detachDueBatches() {
        List<List<HistoryWriteRequest>> batches = new ArrayList<>();
        long now = currentNanoTime();
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, properties.getFlushIntervalMs()));
        admissionLock.readLock().lock();
        try {
            if (closing) {
                return List.of();
            }
            for (String bucketKey : List.copyOf(buckets.keySet())) {
                AtomicReference<List<HistoryWriteRequest>> batchRef = new AtomicReference<>(List.of());
                buckets.computeIfPresent(bucketKey, (ignored, bucket) -> {
                    if (bucket.requests.isEmpty()) {
                        return null;
                    }
                    if (!isDue(bucket, now, intervalNanos)) {
                        return bucket;
                    }
                    List<HistoryWriteRequest> batch = drainLocked(bucket, batchSize());
                    batchRef.set(batch);
                    return bucket.requests.isEmpty() ? null : bucket;
                });
                List<HistoryWriteRequest> batch = batchRef.get();
                if (!batch.isEmpty()) {
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
            // 写锁成功表示所有已进入 admission 临界区的请求都完成了 bucket 所有权转移。
        } finally {
            admissionLock.writeLock().unlock();
        }
    }

    private boolean isDue(Bucket bucket, long now, long intervalNanos) {
        return bucket.firstAcceptedAtNanos > 0L && now - bucket.firstAcceptedAtNanos >= intervalNanos;
    }

    private void submitOwnedFlush(List<HistoryWriteRequest> batch, boolean shutdownOwned, FlushTrigger trigger) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        BatchFlushTask task = new BatchFlushTask(
                flushTaskSequence.incrementAndGet(), List.copyOf(batch), shutdownOwned, trigger);
        registerInFlightFlush();
        ownedFlushTasks.put(task.id(), task);
        flushExecutorSubmittedBatches.increment();
        if (!tryIncrementFlushQueue()) {
            flushExecutorRejectedBatches.increment();
            task.fallback(new RejectedExecutionException("history batch flush queue full"),
                    shutdownOwned, false, true);
            return;
        }
        enqueueSubTableTask(task);
    }

    private BatchWriteAttempt executeBatchWrite(List<HistoryWriteRequest> batch) {
        try {
            HistoryBatchWriteResult result = historyWriteBuffer.writeBatchOrBuffer(batch);
            if (result == null) {
                return new BatchWriteAttempt(null,
                        new IllegalStateException("history batch write returned null result"));
            }
            return new BatchWriteAttempt(result, null);
        } catch (RuntimeException exception) {
            return new BatchWriteAttempt(null, exception);
        }
    }

    private void enqueueSubTableTask(BatchFlushTask task) {
        SubTableFlushState state = subTableFlushStates.computeIfAbsent(
                task.subTableKey(), SubTableFlushState::new);
        SubTableFlushRunner runner = null;
        synchronized (state) {
            state.queue.addLast(task);
            if (!state.runnerSubmitted) {
                state.runnerSubmitted = true;
                runner = new SubTableFlushRunner(state);
                state.runner = runner;
            }
        }
        if (runner != null) {
            submitSubTableRunner(state, runner);
        }
    }

    private void submitSubTableRunner(SubTableFlushState state, SubTableFlushRunner runner) {
        try {
            historyBatchFlushExecutor.execute(runner);
        } catch (RuntimeException exception) {
            fallbackQueuedSubTableTasks(state, exception, false, true);
        }
    }

    private void fallbackQueuedSubTableTasks(SubTableFlushState state,
                                             RuntimeException exception,
                                             boolean shutdownFallback,
                                             boolean submitRejected) {
        List<BatchFlushTask> queued = new ArrayList<>();
        synchronized (state) {
            while (!state.queue.isEmpty()) {
                queued.add(state.queue.removeFirst());
            }
            state.runnerSubmitted = false;
            state.runnerActive = false;
            state.runner = null;
        }
        for (BatchFlushTask task : queued) {
            flushExecutorRejectedBatches.increment();
            task.fallback(exception, shutdownFallback || task.shutdownOwned, true, submitRejected);
        }
    }

    private ClaimedFlushTasks claimOwnedTasks(SubTableFlushState state) {
        List<BatchFlushTask> tasks = claimSameSubTableTasks(state);
        List<SubTableFlushState> ownedStates = new ArrayList<>();
        if (!tasks.isEmpty()) {
            ownedStates.add(state);
        }
        if (!tasks.isEmpty() && tdengineProperties.getWrite().isMultiTableEnabled()) {
            collectOtherSubTableTasks(tasks, ownedStates);
        }
        return new ClaimedFlushTasks(state, ownedStates, tasks);
    }

    private List<BatchFlushTask> claimSameSubTableTasks(SubTableFlushState state) {
        List<BatchFlushTask> tasks = new ArrayList<>();
        int maxRows = maxRowsPerRequest();
        int rows = 0;
        long now = System.nanoTime();
        synchronized (state) {
            while (!state.queue.isEmpty()) {
                BatchFlushTask next = state.queue.peekFirst();
                if (!tasks.isEmpty() && rows + next.batch.size() > maxRows) {
                    break;
                }
                state.queue.removeFirst();
                if (next.claimForRun()) {
                    decrementFlushQueue();
                    recordDbQueueWait(now - next.queuedAtNanos);
                    tasks.add(next);
                    rows += next.batch.size();
                }
            }
        }
        return tasks;
    }

    private void collectOtherSubTableTasks(List<BatchFlushTask> tasks, List<SubTableFlushState> ownedStates) {
        int maxTables = Math.max(1, tdengineProperties.getWrite().getMaxTablesPerRequest());
        int maxRows = maxRowsPerRequest();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, tdengineProperties.getWrite().getAggregationWaitMs()));
        while (distinctBucketCount(tasks) < maxTables && totalRows(tasks) < maxRows) {
            ClaimedOtherSubTableTask claimed = claimOneOtherSubTableTask(tasks, maxRows);
            if (claimed != null) {
                tasks.add(claimed.task());
                ownedStates.add(claimed.state());
                continue;
            }
            if (System.nanoTime() >= deadline) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
        }
    }

    private ClaimedOtherSubTableTask claimOneOtherSubTableTask(List<BatchFlushTask> existingTasks, int maxRows) {
        Set<String> existingSubTables = subTableKeys(existingTasks);
        int rows = totalRows(existingTasks);
        long now = System.nanoTime();
        for (SubTableFlushState state : List.copyOf(subTableFlushStates.values())) {
            if (existingSubTables.contains(state.subTable)) {
                continue;
            }
            if (!tryReserveAggregatedState(state)) {
                continue;
            }
            boolean claimed = false;
            try {
                synchronized (state) {
                    BatchFlushTask next = state.queue.peekFirst();
                    if (next == null || rows + next.batch.size() > maxRows) {
                        continue;
                    }
                    state.queue.removeFirst();
                    if (next.claimForRun()) {
                        decrementFlushQueue();
                        recordDbQueueWait(now - next.queuedAtNanos);
                        claimed = true;
                        return new ClaimedOtherSubTableTask(state, next);
                    }
                }
            } finally {
                if (!claimed) {
                    releaseAggregatedState(state);
                }
            }
        }
        return null;
    }

    private List<HistoryWriteRequest> flatten(List<BatchFlushTask> tasks) {
        int rows = 0;
        for (BatchFlushTask task : tasks) {
            rows += task.batch.size();
        }
        List<HistoryWriteRequest> requests = new ArrayList<>(rows);
        for (BatchFlushTask task : tasks) {
            requests.addAll(task.batch);
        }
        return requests;
    }

    private int totalRows(List<BatchFlushTask> tasks) {
        int rows = 0;
        for (BatchFlushTask task : tasks) {
            rows += task.batch.size();
        }
        return rows;
    }

    private Set<String> subTableKeys(List<BatchFlushTask> tasks) {
        Set<String> keys = new HashSet<>();
        for (BatchFlushTask task : tasks) {
            keys.add(task.subTableKey());
        }
        return keys;
    }

    private int maxRowsPerRequest() {
        return Math.max(batchSize(), tdengineProperties.getWrite().getMaxRowsPerRequest());
    }

    private boolean tryReserveAggregatedState(SubTableFlushState state) {
        SubTableFlushRunner queuedRunner;
        synchronized (state) {
            if (state.queue.isEmpty() || state.runnerActive) {
                return false;
            }
            if (!state.runnerSubmitted) {
                state.runnerSubmitted = true;
                return true;
            }
            queuedRunner = state.runner;
        }
        if (queuedRunner == null || !removeFromExecutorQueue(queuedRunner)) {
            return false;
        }
        synchronized (state) {
            if (state.runnerActive || state.runner != queuedRunner) {
                return false;
            }
            state.runner = null;
            return true;
        }
    }

    private void releaseAggregatedState(SubTableFlushState state) {
        SubTableFlushRunner runner;
        synchronized (state) {
            if (state.queue.isEmpty()) {
                state.runnerSubmitted = false;
                state.runner = null;
                return;
            }
            runner = new SubTableFlushRunner(state);
            state.runner = runner;
        }
        submitSubTableRunner(state, runner);
    }

    private void recordTdengineRequest(List<BatchFlushTask> tasks, int rows) {
        int tables = distinctBucketCount(tasks);
        tdengineWriteRequests.increment();
        tdengineWriteRows.add(rows);
        recordIntSample(rowsPerTdengineRequestSamples, rows);
        recordIntSample(tablesPerTdengineRequestSamples, tables);
        if (tables > 1) {
            multiTableWriteRequests.increment();
            multiTableWriteRows.add(rows);
            multiTableAggregatedBatches.add(tables);
        }
    }

    private ActiveWriteLease beginSubTableWrite(String subTable) {
        AtomicInteger active = activeWritesBySubTable.computeIfAbsent(subTable, ignored -> new AtomicInteger());
        int current = active.incrementAndGet();
        maxConcurrentWritesSameSubTable.accumulateAndGet(current, Math::max);
        if (current > 1) {
            sameSubTableConcurrentWriteCount.increment();
        }
        return new ActiveWriteLease(subTable, System.nanoTime());
    }

    private void finishSubTableWrite(ActiveWriteLease lease) {
        if (lease == null) {
            return;
        }
        AtomicInteger active = activeWritesBySubTable.get(lease.subTable());
        if (active != null) {
            active.updateAndGet(current -> Math.max(0, current - 1));
        }
        recordSubTableWriteLatency(lease.subTable(), System.nanoTime() - lease.startedAtNanos());
    }

    private void recordDbQueueWait(long nanos) {
        recordLongSample(dbQueueWaitNanos, Math.max(0L, nanos));
    }

    private void recordDbExecuteLatency(long nanos) {
        recordLongSample(dbExecuteLatencyNanos, Math.max(0L, nanos));
    }

    private void recordSubTableWriteLatency(String subTable, long nanos) {
        List<Long> samples = subTableWriteLatencyNanos.computeIfAbsent(
                subTable, ignored -> Collections.synchronizedList(new ArrayList<>()));
        recordLongSample(samples, Math.max(0L, nanos));
    }

    private int distinctBucketCount(List<BatchFlushTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        Set<String> bucketKeys = new HashSet<>();
        for (BatchFlushTask task : tasks) {
            if (task.batch.isEmpty()) {
                continue;
            }
            bucketKeys.add(bucketKey(task.batch.get(0)));
        }
        return Math.max(1, bucketKeys.size());
    }

    private void recordBatchWriteAttempt(List<HistoryWriteRequest> batch,
                                         BatchWriteAttempt attempt,
                                         long startedAt,
                                         FlushTrigger trigger) {
        if (attempt.exception() != null) {
            batchWriteFailure.increment();
            fallbackBatch(batch, attempt.exception(), false);
        } else if (attempt.result().directSuccess()) {
            batchWriteSuccess.increment();
        } else {
            batchWriteFailure.increment();
            recordBatchFallback(attempt.result(), false);
        }
        flushedBatches.increment();
        flushedRows.add(batch.size());
        recordBatchSize(batch.size());
        recordTriggerBatchSize(trigger, batch.size());
        recordFlushLatency(System.nanoTime() - startedAt);
    }

    private void recordOwnedBatchWriteAttempt(BatchFlushTask task,
                                              BatchWriteAttempt attempt,
                                              long startedAt,
                                              boolean recordFallbackOutcome) {
        if (attempt.exception() != null) {
            batchWriteFailure.increment();
            fallbackBatch(task.batch, attempt.exception(), false);
        } else if (attempt.result().directSuccess()) {
            batchWriteSuccess.increment();
        } else {
            batchWriteFailure.increment();
            if (recordFallbackOutcome) {
                recordBatchFallback(attempt.result(), false);
            }
        }
        flushedBatches.increment();
        flushedRows.add(task.batch.size());
        recordBatchSize(task.batch.size());
        recordTriggerBatchSize(task.trigger, task.batch.size());
        recordFlushLatency(System.nanoTime() - startedAt);
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

    private void fallbackBatch(List<HistoryWriteRequest> batch,
                               RuntimeException exception,
                               boolean shutdownFallback) {
        HistoryBatchWriteResult result;
        try {
            result = historyWriteBuffer.deferBatchForRetry(batch, exception);
        } catch (RuntimeException fallbackException) {
            log.error("历史批量 fallback 异常，将回退逐条计入既有 fallback 语义，rows={}",
                    batch != null ? batch.size() : 0, fallbackException);
            result = null;
        }
        if (result == null) {
            for (HistoryWriteRequest request : batch) {
                fallback(request, exception, shutdownFallback);
            }
            return;
        }
        recordBatchFallback(result, shutdownFallback);
    }

    private void recordBatchFallback(HistoryBatchWriteResult result, boolean shutdownFallback) {
        fallbackRows.add(result.fallbackRows());
        fallbackRedisRows.add(result.redisBufferedRows());
        fallbackLocalRows.add(result.localBufferedRows());
        fallbackDroppedRows.add(result.droppedRows());
        fallbackDisabledRows.add(result.disabledRows());
        if (shutdownFallback) {
            shutdownDeferredRows.add(result.fallbackRows());
            shutdownNonDurableRows.add(result.localBufferedRows());
            shutdownDroppedRows.add(result.droppedRows());
            shutdownDisabledRows.add(result.disabledRows());
        }
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
        fallbackBatch(batch, exception, true);
    }

    private void fallbackOutstandingFlushTasks(RuntimeException exception) {
        for (BatchFlushTask task : List.copyOf(ownedFlushTasks.values())) {
            task.fallback(exception, true, task.state.get() == FlushTaskState.PENDING, false);
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

    private void waitForInFlightFlushes(long deadlineNanos) {
        synchronized (flushMonitor) {
            while (inFlightFlushes > 0 && System.nanoTime() < deadlineNanos) {
                try {
                    long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                    flushMonitor.wait(Math.min(100L, remainingMs));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.warn("等待历史批量 flush 完成时被中断，将对未完成 batch 进入 shutdown fallback");
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

    private void incrementFlushQueue() {
        int current = flushExecutorQueueCurrent.incrementAndGet();
        flushExecutorQueuePeak.accumulateAndGet(current, Math::max);
    }

    private boolean tryIncrementFlushQueue() {
        int current = flushExecutorQueueCurrent.incrementAndGet();
        if (current > flushExecutorQueueCapacity()) {
            flushExecutorQueueCurrent.decrementAndGet();
            return false;
        }
        flushExecutorQueuePeak.accumulateAndGet(current, Math::max);
        return true;
    }

    private void decrementFlushQueue() {
        flushExecutorQueueCurrent.updateAndGet(current -> Math.max(0, current - 1));
    }

    private void incrementFlushActive() {
        int current = flushExecutorActiveCurrent.incrementAndGet();
        flushExecutorActivePeak.accumulateAndGet(current, Math::max);
    }

    private void decrementFlushActive() {
        flushExecutorActiveCurrent.updateAndGet(current -> Math.max(0, current - 1));
    }

    private boolean removeFromExecutorQueue(Runnable command) {
        try {
            if (historyBatchFlushExecutor instanceof RemovableExecutor removableExecutor) {
                return removableExecutor.remove(command);
            }
            if (historyBatchFlushExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
                ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
                return executor.getQueue().remove(command);
            }
            if (historyBatchFlushExecutor instanceof ThreadPoolExecutor executor) {
                return executor.getQueue().remove(command);
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
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

    private void recordLongSample(List<Long> samples, long value) {
        synchronized (samples) {
            if (samples.size() < METRIC_SAMPLE_LIMIT) {
                samples.add(value);
            }
        }
    }

    private double averageBatchSize() {
        return averageBatchSize(flushedRows.sum(), flushedBatches.sum());
    }

    private double averageBatchSize(long rows, long batches) {
        return batches <= 0L ? 0D : (double) rows / batches;
    }

    private void recordTriggerBatchSize(FlushTrigger trigger, int size) {
        if (trigger == FlushTrigger.SIZE) {
            sizeFlushBatches.increment();
            sizeFlushRows.add(size);
            recordIntSample(sizeBatchSizeSamples, size);
        } else if (trigger == FlushTrigger.TIMER) {
            timerFlushBatches.increment();
            timerFlushRows.add(size);
            recordIntSample(timerBatchSizeSamples, size);
        }
    }

    private void recordIntSample(List<Integer> samples, int size) {
        synchronized (samples) {
            if (samples.size() < METRIC_SAMPLE_LIMIT) {
                samples.add(size);
            }
        }
    }

    private double tdengineBatchCallsPerSecond() {
        return ratePerSecond(flushedBatches.sum());
    }

    private double flushExecutorServiceRatePerSecond() {
        return ratePerSecond(flushExecutorCompletedBatches.sum());
    }

    private double ratePerSecond(long count) {
        long elapsedNanos = Math.max(1L, System.nanoTime() - createdAtNanos);
        return count * 1_000_000_000.0D / elapsedNanos;
    }

    private double flushExecutorQueueUtilization() {
        return Math.max(0D, (double) flushExecutorQueueCurrent.get() / flushExecutorQueueCapacity());
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

    private long sumIntSamples(List<Integer> values) {
        synchronized (values) {
            long sum = 0L;
            for (Integer value : values) {
                if (value != null) {
                    sum += value;
                }
            }
            return sum;
        }
    }

    private double percentileMillis(List<Long> values, double percentile) {
        List<Long> sorted = snapshot(values);
        if (sorted.isEmpty()) {
            return 0D;
        }
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.max(0, index)) / 1_000_000.0D;
    }

    private Map<String, Integer> activeWritesSnapshot() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : activeWritesBySubTable.entrySet()) {
            int active = entry.getValue().get();
            if (active > 0) {
                snapshot.put(entry.getKey(), active);
            }
        }
        return Map.copyOf(snapshot);
    }

    private Map<String, Double> subTableLatencyP95Snapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : subTableWriteLatencyNanos.entrySet()) {
            snapshot.put(entry.getKey(), percentileMillis(entry.getValue(), 0.95D));
        }
        return Map.copyOf(snapshot);
    }

    private <T extends Comparable<? super T>> List<T> snapshot(List<T> values) {
        synchronized (values) {
            return values.stream().sorted().toList();
        }
    }

    private long oldestBufferedAgeMs() {
        long now = currentNanoTime();
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

    private long currentNanoTime() {
        return nanoTimeSupplier.getAsLong();
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

    private record BatchWriteAttempt(HistoryBatchWriteResult result, RuntimeException exception) {
    }

    private enum FlushTaskState {
        PENDING,
        RUNNING,
        DONE,
        FALLBACKED
    }

    private enum FlushTrigger {
        SIZE,
        TIMER,
        SHUTDOWN
    }

    private final class SubTableFlushRunner implements Runnable {

        private final SubTableFlushState state;

        private SubTableFlushRunner(SubTableFlushState state) {
            this.state = state;
        }

        @Override
        public void run() {
            if (!startRunner(state, this)) {
                return;
            }
            try {
                while (true) {
                    ClaimedFlushTasks claimed = claimOwnedTasks(state);
                    if (claimed.tasks().isEmpty()) {
                        return;
                    }
                    processClaimedTasks(claimed);
                }
            } finally {
                finishPrimaryRunner(state);
            }
        }
    }

    private boolean startRunner(SubTableFlushState state, SubTableFlushRunner runner) {
        synchronized (state) {
            if (state.runner != runner || state.runnerActive) {
                return false;
            }
            state.runnerActive = true;
            return true;
        }
    }

    private void processClaimedTasks(ClaimedFlushTasks claimed) {
        List<BatchFlushTask> ownedTasks = claimed.tasks();
        List<HistoryWriteRequest> rows = flatten(ownedTasks);
        List<ActiveWriteLease> leases = new ArrayList<>();
        incrementFlushActive();
        long startedAt = System.nanoTime();
        BatchWriteAttempt attempt = null;
        try {
            for (SubTableFlushState state : claimed.ownedStates()) {
                leases.add(beginSubTableWrite(state.subTable));
            }
            long executeStartedAt = System.nanoTime();
            attempt = executeBatchWrite(rows);
            recordDbExecuteLatency(System.nanoTime() - executeStartedAt);
            recordTdengineRequest(ownedTasks, rows.size());
        } catch (RuntimeException exception) {
            attempt = new BatchWriteAttempt(null, exception);
        } finally {
            for (ActiveWriteLease lease : leases) {
                finishSubTableWrite(lease);
            }
            decrementFlushActive();
        }
        completeOwnedTasks(ownedTasks, attempt, startedAt);
        for (SubTableFlushState state : claimed.ownedStates()) {
            if (state != claimed.primaryState()) {
                finishAggregatedState(state);
            }
        }
    }

    private void completeOwnedTasks(List<BatchFlushTask> ownedTasks,
                                    BatchWriteAttempt attempt,
                                    long startedAt) {
        boolean combinedFallbackRecorded = false;
        for (BatchFlushTask task : ownedTasks) {
            if (task.state.compareAndSet(FlushTaskState.RUNNING, FlushTaskState.DONE)) {
                recordOwnedBatchWriteAttempt(task, attempt, startedAt, !combinedFallbackRecorded);
                if (attempt.exception() == null
                        && attempt.result() != null
                        && !attempt.result().directSuccess()) {
                    combinedFallbackRecorded = true;
                }
                if (task.shutdownOwned) {
                    shutdownFlushedRows.add(task.batch.size());
                }
                flushExecutorCompletedBatches.increment();
                ownedFlushTasks.remove(task.id);
                completeInFlightFlush();
            }
        }
    }

    private void finishPrimaryRunner(SubTableFlushState state) {
        SubTableFlushRunner runner;
        synchronized (state) {
            state.runnerActive = false;
            if (state.queue.isEmpty()) {
                state.runnerSubmitted = false;
                state.runner = null;
                return;
            }
            runner = new SubTableFlushRunner(state);
            state.runner = runner;
        }
        submitSubTableRunner(state, runner);
    }

    private void finishAggregatedState(SubTableFlushState state) {
        SubTableFlushRunner runner;
        synchronized (state) {
            if (state.queue.isEmpty()) {
                state.runnerSubmitted = false;
                state.runner = null;
                return;
            }
            runner = new SubTableFlushRunner(state);
            state.runner = runner;
        }
        submitSubTableRunner(state, runner);
    }

    private static final class SubTableFlushState {
        private final String subTable;
        private final ArrayDeque<BatchFlushTask> queue = new ArrayDeque<>();
        private boolean runnerSubmitted;
        private boolean runnerActive;
        private SubTableFlushRunner runner;

        private SubTableFlushState(String subTable) {
            this.subTable = subTable;
        }
    }

    private record ClaimedFlushTasks(SubTableFlushState primaryState,
                                     List<SubTableFlushState> ownedStates,
                                     List<BatchFlushTask> tasks) {
    }

    private record ClaimedOtherSubTableTask(SubTableFlushState state, BatchFlushTask task) {
    }

    private record ActiveWriteLease(String subTable, long startedAtNanos) {
    }

    private final class BatchFlushTask {

        private final long id;
        private final List<HistoryWriteRequest> batch;
        private final boolean shutdownOwned;
        private final FlushTrigger trigger;
        private final long queuedAtNanos = System.nanoTime();
        private final AtomicReference<FlushTaskState> state = new AtomicReference<>(FlushTaskState.PENDING);

        private BatchFlushTask(long id, List<HistoryWriteRequest> batch, boolean shutdownOwned, FlushTrigger trigger) {
            this.id = id;
            this.batch = batch;
            this.shutdownOwned = shutdownOwned;
            this.trigger = trigger == null ? FlushTrigger.TIMER : trigger;
        }

        private long id() {
            return id;
        }

        private boolean claimForRun() {
            return state.compareAndSet(FlushTaskState.PENDING, FlushTaskState.RUNNING);
        }

        private String subTableKey() {
            return batch.isEmpty() ? "" : bucketKey(batch.get(0));
        }

        private void fallback(RuntimeException exception,
                              boolean shutdownFallback,
                              boolean decrementQueued,
                              boolean submitRejected) {
            while (true) {
                FlushTaskState current = state.get();
                if (current == FlushTaskState.DONE || current == FlushTaskState.FALLBACKED) {
                    return;
                }
                if (state.compareAndSet(current, FlushTaskState.FALLBACKED)) {
                    if (current == FlushTaskState.PENDING && decrementQueued) {
                        if (!submitRejected) {
                            shutdownQueuedBatches.increment();
                        }
                        decrementFlushQueue();
                    }
                    if (submitRejected) {
                        log.warn("历史批量 flush executor 拒绝任务，数据进入既有 fallback，rows={}，reason={}",
                                batch.size(), exception.getClass().getSimpleName());
                    }
                    deferShutdownOrNormalBatch(batch, exception, shutdownFallback);
                    ownedFlushTasks.remove(id);
                    completeInFlightFlush();
                    return;
                }
            }
        }

        private void deferShutdownOrNormalBatch(List<HistoryWriteRequest> rows,
                                                RuntimeException exception,
                                                boolean shutdownFallback) {
            fallbackBatch(rows, exception, shutdownFallback);
        }
    }

    int flushExecutorQueueCapacity() {
        return Math.max(1, properties.getFlushExecutor().getQueueCapacity());
    }

    void admissionObserver(AdmissionObserver admissionObserver) {
        this.admissionObserver = admissionObserver == null ? AdmissionObserver.NOOP : admissionObserver;
    }

    void nanoTimeSupplierForTest(LongSupplier nanoTimeSupplier) {
        this.nanoTimeSupplier = nanoTimeSupplier == null ? System::nanoTime : nanoTimeSupplier;
    }

    interface AdmissionObserver {
        AdmissionObserver NOOP = new AdmissionObserver() {
        };

        default void afterInitialClosingCheck(HistoryWriteRequest request) {
        }

        default void beforeBucketOwnershipTransfer(HistoryWriteRequest request) {
        }
    }

    interface RemovableExecutor extends Executor {

        boolean remove(Runnable command);
    }
}
