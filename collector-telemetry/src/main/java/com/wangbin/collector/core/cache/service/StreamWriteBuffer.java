package com.wangbin.collector.core.cache.service;

import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis Stream 有界写缓冲区，将遥测阶段 admission 与 Redis pipeline I/O 解耦。
 */
@Slf4j
@Component
public class StreamWriteBuffer implements SmartLifecycle, DisposableBean {

    private static final int METRIC_SAMPLE_LIMIT = 20_000;
    private static final String CMD_XADD = "XADD";
    private static final byte[] MAXLEN = bytes("MAXLEN");
    private static final byte[] APPROX = bytes("~");
    private static final byte[] STAR = bytes("*");

    private final RedisTemplate<String, Object> redisTemplate;
    private final TelemetryStreamProperties properties;
    private final Executor writerExecutor;
    private final ArrayBlockingQueue<StreamWriteItem> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean admissionOpen = new AtomicBoolean(false);
    private final AtomicInteger bufferPeak = new AtomicInteger();
    private final AtomicInteger activeBatchRows = new AtomicInteger();
    private final LongAdder admissionAccepted = new LongAdder();
    private final LongAdder admissionRejected = new LongAdder();
    private final LongAdder admissionDropped = new LongAdder();
    private final LongAdder writerBatchCount = new LongAdder();
    private final LongAdder writerRows = new LongAdder();
    private final LongAdder redisPipelineCalls = new LongAdder();
    private final LongAdder redisXaddRows = new LongAdder();
    private final LongAdder redisXaddFailures = new LongAdder();
    private final LongAdder shutdownDroppedRows = new LongAdder();
    private final LongAdder writerLoopFailures = new LongAdder();
    private final StreamMetricReservoir writerBatchSizes = new StreamMetricReservoir(METRIC_SAMPLE_LIMIT);
    private final StreamMetricReservoir redisBatchLatencyNanos = new StreamMetricReservoir(METRIC_SAMPLE_LIMIT);
    private volatile CountDownLatch terminated = new CountDownLatch(0);

    public StreamWriteBuffer(RedisTemplate<String, Object> redisTemplate,
                             TelemetryStreamProperties properties,
                             @Qualifier(TelemetryExecutorNames.STREAM_WRITE) Executor writerExecutor) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.writerExecutor = writerExecutor;
        this.queue = new ArrayBlockingQueue<>(bufferCapacity(properties));
    }

    /**
     * 尝试快速接收一条待写入的 Redis Stream 记录。
     */
    public OfferResult offer(Map<String, String> fields) {
        if (fields == null || fields.isEmpty() || !admissionOpen.get()) {
            admissionRejected.increment();
            admissionDropped.increment();
            return OfferResult.REJECTED_CLOSED;
        }
        if (!queue.offer(new StreamWriteItem(fields))) {
            admissionRejected.increment();
            admissionDropped.increment();
            return OfferResult.REJECTED_FULL;
        }
        admissionAccepted.increment();
        updateBufferPeak();
        return OfferResult.ACCEPTED;
    }

    /**
     * 返回当前缓冲区和 writer 的观测快照。
     */
    public StreamWriteBufferMetrics metrics() {
        return new StreamWriteBufferMetrics(
                admissionAccepted.sum(),
                admissionRejected.sum(),
                admissionDropped.sum(),
                queue.size(),
                bufferPeak.get(),
                queue.remainingCapacity() + queue.size(),
                writerBatchCount.sum(),
                writerRows.sum(),
                writerBatchSizes.percentileInt(0.50D),
                writerBatchSizes.percentileInt(0.95D),
                writerBatchSizes.percentileInt(0.99D),
                redisPipelineCalls.sum(),
                redisXaddRows.sum(),
                redisXaddFailures.sum(),
                redisBatchLatencyNanos.percentileMillis(0.50D),
                redisBatchLatencyNanos.percentileMillis(0.95D),
                redisBatchLatencyNanos.percentileMillis(0.99D),
                shutdownDroppedRows.sum(),
                writerLoopFailures.sum());
    }

    /**
     * 重置观测计数，不清空已接收但未写出的业务数据。
     */
    public void resetMetrics() {
        admissionAccepted.reset();
        admissionRejected.reset();
        admissionDropped.reset();
        writerBatchCount.reset();
        writerRows.reset();
        redisPipelineCalls.reset();
        redisXaddRows.reset();
        redisXaddFailures.reset();
        shutdownDroppedRows.reset();
        writerLoopFailures.reset();
        writerBatchSizes.reset();
        redisBatchLatencyNanos.reset();
        bufferPeak.set(queue.size());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        admissionOpen.set(true);
        terminated = new CountDownLatch(1);
        try {
            writerExecutor.execute(this::writerLoop);
        } catch (RejectedExecutionException exception) {
            running.set(false);
            admissionOpen.set(false);
            terminated.countDown();
            log.error("Redis Stream writer 执行器拒绝启动，后续 Stream admission 将被显式拒绝", exception);
        }
    }

    @Override
    public void stop() {
        stop(null);
    }

    @Override
    public void stop(Runnable callback) {
        admissionOpen.set(false);
        running.set(false);
        long timeoutMs = shutdownTimeoutMs();
        try {
            if (!terminated.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                int remaining = queue.size() + activeBatchRows.get();
                if (remaining > 0) {
                    shutdownDroppedRows.add(remaining);
                    admissionDropped.add(remaining);
                    queue.clear();
                    log.warn("Redis Stream writer 关闭超时，剩余缓冲数据已显式丢弃，rows={}", remaining);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            int remaining = queue.size() + activeBatchRows.get();
            if (remaining > 0) {
                shutdownDroppedRows.add(remaining);
                admissionDropped.add(remaining);
                queue.clear();
            }
        } finally {
            if (callback != null) {
                callback.run();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void destroy() {
        stop();
    }

    private void writerLoop() {
        try {
            while (running.get() || !queue.isEmpty()) {
                try {
                    drainOnce();
                } catch (Exception exception) {
                    writerLoopFailures.increment();
                    log.error("Redis Stream writer 执行异常，writer 将继续下一轮 drain", exception);
                }
            }
        } finally {
            terminated.countDown();
        }
    }

    private void drainOnce() throws InterruptedException {
        int batchSize = batchSize();
        List<StreamWriteItem> batch = new ArrayList<>(batchSize);
        StreamWriteItem first = queue.poll(flushIntervalMs(), TimeUnit.MILLISECONDS);
        if (first == null) {
            return;
        }
        batch.add(first);
        fillBatch(batch, batchSize);
        writeBatch(batch);
    }

    private void fillBatch(List<StreamWriteItem> batch, int batchSize) throws InterruptedException {
        queue.drainTo(batch, batchSize - batch.size());
        if (batch.size() >= batchSize || !running.get()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMs());
        while (batch.size() < batchSize) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            StreamWriteItem next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
    }

    private void writeBatch(List<StreamWriteItem> batch) {
        if (batch.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        activeBatchRows.set(batch.size());
        try {
            List<Object> results = redisTemplate.execute((RedisCallback<List<Object>>) connection -> {
                connection.openPipeline();
                for (StreamWriteItem item : batch) {
                    connection.execute(CMD_XADD, xaddArgs(item.fields()));
                }
                return connection.closePipeline();
            });
            redisPipelineCalls.increment();
            writerBatchCount.increment();
            writerRows.add(batch.size());
            writerBatchSizes.add(batch.size());
            long failures = failedRows(results, batch.size());
            long success = batch.size() - failures;
            if (success > 0L) {
                redisXaddRows.add(success);
            }
            if (failures > 0L) {
                redisXaddFailures.add(failures);
                log.error("Redis Stream pipeline 写入存在失败，key={}，batchSize={}，failedRows={}",
                        properties.getKey(), batch.size(), failures);
            }
        } catch (Exception exception) {
            redisPipelineCalls.increment();
            writerBatchCount.increment();
            writerRows.add(batch.size());
            writerBatchSizes.add(batch.size());
            redisXaddFailures.add(batch.size());
            log.error("Redis Stream pipeline 写入失败，key={}，batchSize={}",
                    properties.getKey(), batch.size(), exception);
        } finally {
            activeBatchRows.set(0);
            redisBatchLatencyNanos.add(System.nanoTime() - startedAt);
        }
    }

    private long failedRows(List<Object> results, int expectedRows) {
        if (results == null || results.size() != expectedRows) {
            return expectedRows;
        }
        long failures = 0L;
        for (Object result : results) {
            if (result instanceof Throwable) {
                failures++;
            }
        }
        return failures;
    }

    private byte[][] xaddArgs(Map<String, String> fields) {
        List<byte[]> args = new ArrayList<>();
        args.add(bytes(properties.getKey()));
        if (properties.getRetentionMode() == StreamRetentionMode.COUNT && properties.getMaxLength() > 0) {
            args.add(MAXLEN);
            if (properties.isApproximateTrim()) {
                args.add(APPROX);
            }
            args.add(bytes(String.valueOf(properties.getMaxLength())));
        }
        args.add(STAR);
        appendFields(args, fields);
        return args.toArray(new byte[0][]);
    }

    private static void appendFields(List<byte[]> args, Map<String, String> fields) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            args.add(bytes(entry.getKey()));
            args.add(bytes(entry.getValue()));
        }
    }

    private void updateBufferPeak() {
        int current = queue.size();
        while (true) {
            int previous = bufferPeak.get();
            if (current <= previous || bufferPeak.compareAndSet(previous, current)) {
                return;
            }
        }
    }

    private int batchSize() {
        return Math.max(1, properties.getBuffer().getBatchSize());
    }

    private long flushIntervalMs() {
        return Math.max(1L, properties.getBuffer().getFlushIntervalMs());
    }

    private long shutdownTimeoutMs() {
        return Math.max(1L, properties.getBuffer().getShutdownTimeoutMs());
    }

    private static int bufferCapacity(TelemetryStreamProperties properties) {
        return Math.max(1, properties.getBuffer().getCapacity());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public enum OfferResult {
        ACCEPTED,
        REJECTED_FULL,
        REJECTED_CLOSED
    }

    private record StreamWriteItem(Map<String, String> fields) {
    }
}
