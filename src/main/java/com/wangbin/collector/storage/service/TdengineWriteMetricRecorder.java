package com.wangbin.collector.storage.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * TDengine 写入路径指标，使用固定容量环形样本，避免长时间压测只记录窗口前段数据。
 */
@Component
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TdengineWriteMetricRecorder {

    private static final int DEFAULT_SAMPLE_LIMIT = 20_000;

    private final LongAdder writeRequests = new LongAdder();
    private final LongAdder writtenRows = new LongAdder();
    private final LongAdder singleTableWriteRequests = new LongAdder();
    private final LongAdder multiTableWriteRequests = new LongAdder();
    private final LongAdder writeFailures = new LongAdder();
    private final LongAdder ensureSubTableCalls = new LongAdder();
    private final LongAdder ensureSubTableCacheHits = new LongAdder();
    private final LongAdder ensureSubTableCacheMisses = new LongAdder();
    private final LongReservoir rowsPerRequestSamples;
    private final LongReservoir tablesPerRequestSamples;
    private final LongReservoir connectionAcquireLatencyNanos;
    private final LongReservoir sqlBuildLatencyNanos;
    private final LongReservoir dbExecuteLatencyNanos;
    private final LongReservoir totalWriteLatencyNanos;
    private final AtomicLong startedAtNanos = new AtomicLong(System.nanoTime());

    public TdengineWriteMetricRecorder() {
        this(DEFAULT_SAMPLE_LIMIT);
    }

    TdengineWriteMetricRecorder(int sampleLimit) {
        int capacity = Math.max(1, sampleLimit);
        this.rowsPerRequestSamples = new LongReservoir(capacity);
        this.tablesPerRequestSamples = new LongReservoir(capacity);
        this.connectionAcquireLatencyNanos = new LongReservoir(capacity);
        this.sqlBuildLatencyNanos = new LongReservoir(capacity);
        this.dbExecuteLatencyNanos = new LongReservoir(capacity);
        this.totalWriteLatencyNanos = new LongReservoir(capacity);
    }

    public void recordSuccess(TdengineWriteOutcome outcome) {
        if (outcome == null) {
            return;
        }
        writeRequests.increment();
        writtenRows.add(outcome.rows());
        if (outcome.multiTable()) {
            multiTableWriteRequests.increment();
        } else {
            singleTableWriteRequests.increment();
        }
        recordSamples(outcome);
    }

    public void recordFailure(TdengineWriteOutcome outcome) {
        writeFailures.increment();
        if (outcome != null) {
            recordSamples(outcome);
        }
    }

    public void recordEnsureSubTableCall() {
        ensureSubTableCalls.increment();
    }

    public void recordEnsureSubTableCacheHit() {
        ensureSubTableCacheHits.increment();
    }

    public void recordEnsureSubTableCacheMiss() {
        ensureSubTableCacheMisses.increment();
    }

    public TdengineWriteMetrics snapshot() {
        long requests = writeRequests.sum();
        long rows = writtenRows.sum();
        LongReservoir.Snapshot rowsPerRequest = rowsPerRequestSamples.snapshot();
        LongReservoir.Snapshot tablesPerRequest = tablesPerRequestSamples.snapshot();
        LongReservoir.Snapshot connectionAcquire = connectionAcquireLatencyNanos.snapshot();
        LongReservoir.Snapshot sqlBuild = sqlBuildLatencyNanos.snapshot();
        LongReservoir.Snapshot dbExecute = dbExecuteLatencyNanos.snapshot();
        LongReservoir.Snapshot totalWrite = totalWriteLatencyNanos.snapshot();
        return new TdengineWriteMetrics(
                requests,
                rows,
                singleTableWriteRequests.sum(),
                multiTableWriteRequests.sum(),
                writeFailures.sum(),
                ensureSubTableCalls.sum(),
                ensureSubTableCacheHits.sum(),
                ensureSubTableCacheMisses.sum(),
                requests <= 0L ? 0D : (double) rows / requests,
                (int) rowsPerRequest.percentile(0.95D),
                (int) rowsPerRequest.max(),
                requests <= 0L ? 0D : (double) tablesPerRequest.sum() / requests,
                (int) tablesPerRequest.percentile(0.95D),
                (int) tablesPerRequest.max(),
                totalWrite.percentileMillis(0.50D),
                totalWrite.percentileMillis(0.95D),
                totalWrite.percentileMillis(0.99D),
                connectionAcquire.percentileMillis(0.50D),
                connectionAcquire.percentileMillis(0.95D),
                connectionAcquire.percentileMillis(0.99D),
                sqlBuild.percentileMillis(0.50D),
                sqlBuild.percentileMillis(0.95D),
                sqlBuild.percentileMillis(0.99D),
                dbExecute.percentileMillis(0.50D),
                dbExecute.percentileMillis(0.95D),
                dbExecute.percentileMillis(0.99D),
                totalWrite.percentileMillis(0.50D),
                totalWrite.percentileMillis(0.95D),
                totalWrite.percentileMillis(0.99D),
                rowsPerRequest.count(),
                rowsPerRequest.totalRecorded(),
                rowsPerRequest.overwrittenSamples(),
                ratePerSecond(requests),
                ratePerSecond(rows));
    }

    public void reset() {
        writeRequests.reset();
        writtenRows.reset();
        singleTableWriteRequests.reset();
        multiTableWriteRequests.reset();
        writeFailures.reset();
        ensureSubTableCalls.reset();
        ensureSubTableCacheHits.reset();
        ensureSubTableCacheMisses.reset();
        rowsPerRequestSamples.reset();
        tablesPerRequestSamples.reset();
        connectionAcquireLatencyNanos.reset();
        sqlBuildLatencyNanos.reset();
        dbExecuteLatencyNanos.reset();
        totalWriteLatencyNanos.reset();
        startedAtNanos.set(System.nanoTime());
    }

    private void recordSamples(TdengineWriteOutcome outcome) {
        rowsPerRequestSamples.add(outcome.rows());
        tablesPerRequestSamples.add(outcome.tables());
        connectionAcquireLatencyNanos.add(outcome.connectionAcquireNanos());
        sqlBuildLatencyNanos.add(outcome.sqlBuildNanos());
        dbExecuteLatencyNanos.add(outcome.dbExecuteNanos());
        totalWriteLatencyNanos.add(outcome.totalWriteNanos());
    }

    private double ratePerSecond(long count) {
        long elapsedNanos = Math.max(1L, System.nanoTime() - startedAtNanos.get());
        return count * 1_000_000_000.0D / elapsedNanos;
    }

    /**
     * 固定容量环形 long 样本，add 路径只做一次原子递增和数组写入。
     */
    static final class LongReservoir {

        private final AtomicLongArray values;
        private final int capacity;
        private final AtomicLong sequence = new AtomicLong();
        private final LongAdder totalRecorded = new LongAdder();

        LongReservoir(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.values = new AtomicLongArray(this.capacity);
        }

        void add(long value) {
            long slotSequence = sequence.getAndIncrement();
            int slot = Math.floorMod(slotSequence, capacity);
            values.set(slot, Math.max(0L, value));
            totalRecorded.increment();
        }

        Snapshot snapshot() {
            long total = Math.max(0L, totalRecorded.sum());
            int count = (int) Math.min(total, capacity);
            long[] copy = new long[count];
            for (int index = 0; index < count; index++) {
                copy[index] = values.get(index);
            }
            Arrays.sort(copy);
            return new Snapshot(copy, total, capacity);
        }

        void reset() {
            sequence.set(0L);
            totalRecorded.reset();
            for (int index = 0; index < capacity; index++) {
                values.set(index, 0L);
            }
        }

        void sequenceForTest(long value) {
            sequence.set(value);
        }

        record Snapshot(long[] sortedValues, long totalRecorded, int capacity) {

            public int count() {
                return sortedValues.length;
            }

            public long totalRecorded() {
                return totalRecorded;
            }

            long overwrittenSamples() {
                return Math.max(0L, totalRecorded - capacity);
            }

            long max() {
                return sortedValues.length == 0 ? 0L : sortedValues[sortedValues.length - 1];
            }

            long sum() {
                long result = 0L;
                for (long value : sortedValues) {
                    result += value;
                }
                return result;
            }

            long percentile(double percentile) {
                if (sortedValues.length == 0) {
                    return 0L;
                }
                int index = Math.min(sortedValues.length - 1,
                        (int) Math.ceil(sortedValues.length * percentile) - 1);
                return sortedValues[Math.max(0, index)];
            }

            double percentileMillis(double percentile) {
                return percentile(percentile) / 1_000_000.0D;
            }
        }
    }
}
