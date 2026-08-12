package com.wangbin.collector.storage.buffer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.logging.RateLimitedLogReporter;
import com.wangbin.collector.storage.service.TimeSeriesService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 历史数据写入缓冲器，Redis 队列承担写失败恢复，本地有界队列承担 Redis 故障降级。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryWriteBuffer {

    private final TimeSeriesService timeSeriesService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HistoryBufferProperties properties;
    private final ObjectProvider<HistoryBatchWriter> historyBatchWriterProvider;
    private final BlockingQueue<HistoryWriteRequest> localQueue;
    private final RateLimitedLogReporter overloadLogReporter = new RateLimitedLogReporter(log);
    private static final int METRIC_SAMPLE_LIMIT = 10_000;
    private final LongAdder writeFailureRedisBuffered = new LongAdder();
    private final LongAdder rejectedRedisBuffered = new LongAdder();
    private final LongAdder writeFailureLocalBuffered = new LongAdder();
    private final LongAdder rejectedLocalBuffered = new LongAdder();
    private final LongAdder writeFailureDropped = new LongAdder();
    private final LongAdder rejectedDropped = new LongAdder();
    private final LongAdder writeFailureDisabled = new LongAdder();
    private final LongAdder rejectedDisabled = new LongAdder();
    private final LongAdder replayClaimedRows = new LongAdder();
    private final LongAdder replaySuccessfulRows = new LongAdder();
    private final LongAdder replayFailedRows = new LongAdder();
    private final LongAdder replayBatchCount = new LongAdder();
    private final LongAdder replayPausedForLivePressureCount = new LongAdder();
    private final AtomicInteger replayProcessingRows = new AtomicInteger();
    private final LongAdder batchFallbackRedisRows = new LongAdder();
    private final LongAdder batchFallbackRedisOps = new LongAdder();
    private final LongAdder batchFallbackLocalRows = new LongAdder();
    private final LongAdder batchFallbackDroppedRows = new LongAdder();
    private final List<Integer> replayBatchSizeSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> replayBatchWriteLatencyNanos = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> batchFallbackLatencyNanos = Collections.synchronizedList(new ArrayList<>());
    private final long createdAtNanos = System.nanoTime();
    private volatile Supplier<LivePressureSnapshot> livePressureSupplier;

    /**
     * 创建历史写入缓冲器。
     */
    public HistoryWriteBuffer(TimeSeriesService timeSeriesService,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              HistoryBufferProperties properties) {
        this(timeSeriesService, redisTemplate, objectMapper, properties, null);
    }

    /**
     * 创建历史写入缓冲器，恢复路径通过 ObjectProvider 延迟读取实时写入压力，避免 Spring 构造期循环依赖。
     */
    @Autowired
    public HistoryWriteBuffer(TimeSeriesService timeSeriesService,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              HistoryBufferProperties properties,
                              ObjectProvider<HistoryBatchWriter> historyBatchWriterProvider) {
        this.timeSeriesService = timeSeriesService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        this.properties = properties;
        this.historyBatchWriterProvider = historyBatchWriterProvider;
        this.localQueue = new ArrayBlockingQueue<>(Math.max(1, properties.getLocalQueueCapacity()));
    }

    /**
     * 正常写入路径，优先同步写 TDengine，失败后进入既有缓冲链路。
     */
    public void writeOrBuffer(HistoryWriteRequest request) {
        try {
            write(request);
        } catch (RuntimeException exception) {
            if (!properties.isEnabled()) {
                incrementDisabled(BufferReason.WRITE_FAILURE);
                log.error("TDengine 写入失败，且历史缓冲已关闭，数据未进入补偿链路，设备={}，点位={}",
                        request.getDeviceId(), pointId(request), exception);
                throw exception;
            }
            buffer(request, exception, BufferReason.WRITE_FAILURE);
        }
    }

    /**
     * 正常批量写入路径，批量失败时逐条进入既有 Redis/local fallback。
     */
    public HistoryBatchWriteResult writeBatchOrBuffer(List<HistoryWriteRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return HistoryBatchWriteResult.empty();
        }
        int rows = countRows(requests);
        try {
            writeBatch(requests);
            return HistoryBatchWriteResult.directSuccess(rows);
        } catch (RuntimeException exception) {
            if (!properties.isEnabled()) {
                writeFailureDisabled.add(rows);
                log.error("TDengine 批量写入失败，且历史缓冲已关闭，整批数据未进入补偿链路，rows={}", rows, exception);
                return HistoryBatchWriteResult.disabled(rows);
            }
            return bufferBatch(requests, exception, BufferReason.WRITE_FAILURE);
        }
    }

    /**
     * 执行器过载时的延迟写入路径，不在调用线程同步访问 TDengine。
     */
    public HistoryBufferOutcome deferForRetry(HistoryWriteRequest request, RuntimeException cause) {
        if (!properties.isEnabled()) {
            incrementDisabled(BufferReason.REJECTION);
            log.error("History stage 执行器过载，但历史缓冲已关闭，数据未进入补偿链路，设备={}，点位={}",
                    request != null ? request.getDeviceId() : null,
                    request != null ? pointId(request) : null,
                    cause);
            return HistoryBufferOutcome.DISABLED;
        }
        RuntimeException reason = cause != null
                ? cause : new IllegalStateException("history stage rejected");
        return buffer(request, reason, BufferReason.REJECTION);
    }

    /**
     * 批量延迟写入路径，供 History batch flush executor 拒绝或失败时快速进入既有可靠 fallback。
     */
    public HistoryBatchWriteResult deferBatchForRetry(List<HistoryWriteRequest> requests, RuntimeException cause) {
        RuntimeException reason = cause != null
                ? cause : new IllegalStateException("history batch fallback");
        return bufferBatch(requests, reason, BufferReason.REJECTION);
    }

    /**
     * 优先回放 Redis 处理队列，再处理 Redis 故障期间形成的本地队列。
     */
    @Scheduled(fixedDelayString = "${telemetry.tdengine.buffer.replay-interval-ms:500}")
    public void replay() {
        if (!properties.isEnabled()) {
            return;
        }
        int batches = replayBatchesAllowedByLivePressure();
        for (int index = 0; index < batches; index++) {
            if (replayRedisQueue() == 0) {
                break;
            }
        }
        replayLocalQueue(batches);
    }

    /**
     * 停机时按现有语义做一次有限回放。
     */
    @PreDestroy
    public void shutdown() {
        replay();
    }

    /**
     * 返回历史缓冲队列与过载补偿计数。
     */
    public HistoryBufferMetrics metrics() {
        try {
            return newMetrics(
                    listSize(properties.getPendingKey()),
                    listSize(properties.getProcessingKey()),
                    listSize(properties.getDeadLetterKey()));
        } catch (RuntimeException exception) {
            return newMetrics(-1L, -1L, -1L);
        }
    }

    private HistoryBufferMetrics newMetrics(long redisPending,
                                            long redisProcessing,
                                            long redisDeadLetter) {
        return new HistoryBufferMetrics(
                redisPending,
                redisProcessing,
                redisDeadLetter,
                localQueue.size(),
                properties.getLocalQueueCapacity(),
                writeFailureRedisBuffered.sum(),
                rejectedRedisBuffered.sum(),
                writeFailureLocalBuffered.sum(),
                rejectedLocalBuffered.sum(),
                writeFailureDropped.sum(),
                rejectedDropped.sum(),
                writeFailureDisabled.sum(),
                rejectedDisabled.sum(),
                replayClaimedRows.sum(),
                replaySuccessfulRows.sum(),
                replayFailedRows.sum(),
                replayBatchCount.sum(),
                replayAverageBatchSize(),
                percentileInt(replayBatchSizeSamples, 0.95D),
                maxInt(replayBatchSizeSamples),
                replayRowsPerSecond(),
                percentileMillis(replayBatchWriteLatencyNanos, 0.50D),
                percentileMillis(replayBatchWriteLatencyNanos, 0.95D),
                percentileMillis(replayBatchWriteLatencyNanos, 0.99D),
                replayPausedForLivePressureCount.sum(),
                replayProcessingRows.get(),
                batchFallbackRedisRows.sum(),
                batchFallbackRedisOps.sum(),
                batchFallbackLocalRows.sum(),
                batchFallbackDroppedRows.sum(),
                percentileMillis(batchFallbackLatencyNanos, 0.50D),
                percentileMillis(batchFallbackLatencyNanos, 0.95D),
                percentileMillis(batchFallbackLatencyNanos, 0.99D),
                livePressureSnapshot().queueUtilization());
    }

    private HistoryBufferOutcome buffer(HistoryWriteRequest request, RuntimeException cause, BufferReason reason) {
        if (request == null) {
            incrementDropped(reason);
            return HistoryBufferOutcome.DROPPED;
        }
        try {
            redisTemplate.opsForList().leftPush(properties.getPendingKey(), serialize(request));
            incrementRedisBuffered(reason);
            overloadLogReporter.warn("history-redis-buffered-" + reason.name(),
                    "{}，数据已进入 Redis 历史待写队列，设备={}，点位={}，原因={}",
                    reason.message(), request.getDeviceId(), pointId(request), failureMessage(cause));
            return HistoryBufferOutcome.REDIS_BUFFERED;
        } catch (RuntimeException redisException) {
            if (!localQueue.offer(request)) {
                incrementDropped(reason);
                log.error("{}，且本地历史降级队列已满，数据明确丢弃，设备={}，点位={}",
                        reason.message(), request.getDeviceId(), pointId(request), redisException);
                return HistoryBufferOutcome.DROPPED;
            }
            incrementLocalBuffered(reason);
            overloadLogReporter.warn("history-local-buffered-" + reason.name(),
                    "{}，Redis 历史待写队列不可用，数据已进入本地有界降级队列，设备={}，点位={}，原因={}",
                    reason.message(), request.getDeviceId(), pointId(request), failureMessage(redisException));
            return HistoryBufferOutcome.LOCAL_BUFFERED;
        }
    }

    private HistoryBatchWriteResult bufferBatch(List<HistoryWriteRequest> requests,
                                                RuntimeException cause,
                                                BufferReason reason) {
        int rows = countRows(requests);
        if (rows <= 0) {
            return new HistoryBatchWriteResult(false, 0, 0, 0, 0, 0);
        }
        if (!properties.isEnabled()) {
            incrementDisabled(reason, rows);
            return HistoryBatchWriteResult.disabled(rows);
        }
        long startedAt = System.nanoTime();
        List<HistoryWriteRequest> validRequests = new ArrayList<>(rows);
        List<String> payloads = new ArrayList<>(rows);
        int dropped = 0;
        for (HistoryWriteRequest request : requests) {
            if (request == null) {
                dropped++;
                incrementDropped(reason);
                continue;
            }
            try {
                payloads.add(serialize(request));
                validRequests.add(request);
            } catch (RuntimeException exception) {
                dropped++;
                incrementDropped(reason);
                log.error("{}，历史待写消息序列化失败，数据明确丢弃，设备={}，点位={}",
                        reason.message(), request.getDeviceId(), pointId(request), exception);
            }
        }
        try {
            if (!payloads.isEmpty()) {
                redisTemplate.opsForList().leftPushAll(properties.getPendingKey(), payloads);
                incrementRedisBuffered(reason, payloads.size());
                batchFallbackRedisRows.add(payloads.size());
                batchFallbackRedisOps.increment();
                overloadLogReporter.warn("history-redis-buffered-batch-" + reason.name(),
                        "{}，批量数据已进入 Redis 历史待写队列，rows={}，原因={}",
                        reason.message(), payloads.size(), failureMessage(cause));
            }
            batchFallbackDroppedRows.add(dropped);
            recordBatchFallbackLatency(System.nanoTime() - startedAt);
            return new HistoryBatchWriteResult(false, rows, payloads.size(), 0, dropped, 0);
        } catch (RuntimeException redisException) {
            int localBuffered = 0;
            int localDropped = dropped;
            for (HistoryWriteRequest request : validRequests) {
                if (localQueue.offer(request)) {
                    localBuffered++;
                } else {
                    localDropped++;
                }
            }
            incrementLocalBuffered(reason, localBuffered);
            incrementDropped(reason, localDropped - dropped);
            batchFallbackLocalRows.add(localBuffered);
            batchFallbackDroppedRows.add(localDropped);
            if (localDropped > dropped) {
                log.error("{}，Redis 批量历史待写队列不可用，且本地历史降级队列已满，部分数据明确丢弃，rows={}，local={}，dropped={}",
                        reason.message(), rows, localBuffered, localDropped - dropped, redisException);
            } else {
                overloadLogReporter.warn("history-local-buffered-batch-" + reason.name(),
                        "{}，Redis 批量历史待写队列不可用，数据已进入本地有界降级队列，rows={}，原因={}",
                        reason.message(), localBuffered, failureMessage(redisException));
            }
            recordBatchFallbackLatency(System.nanoTime() - startedAt);
            return new HistoryBatchWriteResult(false, rows, 0, localBuffered, localDropped, 0);
        }
    }

    private int replayRedisQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        List<String> claimed;
        try {
            claimed = claimRedisBatch(batchSize);
        } catch (RuntimeException exception) {
            log.warn("读取历史数据 Redis 待写队列失败", exception);
            return 0;
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        replayClaimedRows.add(claimed.size());
        replayProcessingRows.addAndGet(claimed.size());
        try {
            List<HistoryWriteRequest> requests = new ArrayList<>(claimed.size());
            List<String> validJsons = new ArrayList<>(claimed.size());
            for (String json : claimed) {
                try {
                    requests.add(deserialize(json));
                    validJsons.add(json);
                } catch (JsonProcessingException exception) {
                    if (!moveToDeadLetter(json, exception)) {
                        return 0;
                    }
                }
            }
            if (requests.isEmpty()) {
                return claimed.size();
            }
            long startedAt = System.nanoTime();
            try {
                writeBatch(requests);
                recordReplayBatch(requests.size(), System.nanoTime() - startedAt);
                replaySuccessfulRows.add(requests.size());
            } catch (RuntimeException exception) {
                replayFailedRows.add(requests.size());
                log.warn("历史数据批量回放失败，将在下一轮继续重试", exception);
                return 0;
            }
            try {
                removeOwnedProcessing(validJsons);
            } catch (RuntimeException exception) {
                log.warn("历史批量回放已写库，但 Redis processing 删除失败，下次可能重复写入", exception);
            }
            return claimed.size();
        } finally {
            replayProcessingRows.addAndGet(-claimed.size());
        }
    }

    private List<String> claimRedisBatch(int batchSize) {
        List<String> processing = redisTemplate.opsForList().range(
                properties.getProcessingKey(), 0L, batchSize - 1L);
        if (processing != null && !processing.isEmpty()) {
            return processing;
        }
        List<String> claimed = new ArrayList<>(batchSize);
        for (int index = 0; index < batchSize; index++) {
            String json = redisTemplate.opsForList().rightPopAndLeftPush(
                    properties.getPendingKey(), properties.getProcessingKey());
            if (json == null) {
                break;
            }
            claimed.add(json);
        }
        return claimed;
    }

    private void removeOwnedProcessing(List<String> validJsons) {
        for (String json : validJsons) {
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
        }
    }

    private boolean moveToDeadLetter(String json, Exception exception) {
        try {
            redisTemplate.opsForList().leftPush(properties.getDeadLetterKey(), json);
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
            log.error("历史数据待写消息无法反序列化，已转入隔离队列", exception);
            return true;
        } catch (RuntimeException redisException) {
            exception.addSuppressed(redisException);
            log.error("历史数据待写消息无法反序列化，且转入隔离队列失败，将保留 processing 等待下次回放", exception);
            return false;
        }
    }

    private void replayLocalQueue(int maxBatches) {
        if (maxBatches <= 0) {
            return;
        }
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int batchIndex = 0; batchIndex < maxBatches; batchIndex++) {
            List<HistoryWriteRequest> requests = new ArrayList<>(batchSize);
            localQueue.drainTo(requests, batchSize);
            if (requests.isEmpty()) {
                return;
            }
            try {
                long startedAt = System.nanoTime();
                writeBatch(requests);
                recordReplayBatch(requests.size(), System.nanoTime() - startedAt);
                replaySuccessfulRows.add(requests.size());
            } catch (RuntimeException exception) {
                replayFailedRows.add(requests.size());
                for (HistoryWriteRequest request : requests) {
                    if (!localQueue.offer(request)) {
                        writeFailureDropped.increment();
                    }
                }
                log.warn("历史数据本地降级队列批量回放失败，将在下一轮继续重试", exception);
                return;
            }
        }
    }

    private void write(HistoryWriteRequest request) {
        timeSeriesService.append(
                request.getDeviceId(),
                request.getProtocolType(),
                request.getPoint(),
                request.getProcessResult(),
                request.getEventTs());
    }

    private void writeBatch(List<HistoryWriteRequest> requests) {
        List<TimeSeriesService.AppendRequest> appendRequests = new ArrayList<>(requests.size());
        for (HistoryWriteRequest request : requests) {
            if (request == null) {
                continue;
            }
            appendRequests.add(new TimeSeriesService.AppendRequest(
                    request.getDeviceId(),
                    request.getProtocolType(),
                    request.getPoint(),
                    request.getProcessResult(),
                    request.getEventTs()));
        }
        timeSeriesService.appendBatch(appendRequests);
    }

    private int countRows(List<HistoryWriteRequest> requests) {
        int rows = 0;
        for (HistoryWriteRequest request : requests) {
            if (request != null) {
                rows++;
            }
        }
        return rows;
    }

    private String serialize(HistoryWriteRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化历史数据待写消息失败", exception);
        }
    }

    private HistoryWriteRequest deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, HistoryWriteRequest.class);
    }

    private String pointId(HistoryWriteRequest request) {
        return request.getPoint() == null ? null : request.getPoint().getPointId();
    }

    private long listSize(String key) {
        Long size = redisTemplate.opsForList().size(key);
        return size == null ? 0L : size;
    }

    private String failureMessage(RuntimeException exception) {
        if (exception == null) {
            return "unknown";
        }
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private void incrementRedisBuffered(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedRedisBuffered.increment();
        } else {
            writeFailureRedisBuffered.increment();
        }
    }

    private void incrementRedisBuffered(BufferReason reason, int rows) {
        if (rows <= 0) {
            return;
        }
        if (reason == BufferReason.REJECTION) {
            rejectedRedisBuffered.add(rows);
        } else {
            writeFailureRedisBuffered.add(rows);
        }
    }

    private void incrementLocalBuffered(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedLocalBuffered.increment();
        } else {
            writeFailureLocalBuffered.increment();
        }
    }

    private void incrementLocalBuffered(BufferReason reason, int rows) {
        if (rows <= 0) {
            return;
        }
        if (reason == BufferReason.REJECTION) {
            rejectedLocalBuffered.add(rows);
        } else {
            writeFailureLocalBuffered.add(rows);
        }
    }

    private void incrementDropped(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedDropped.increment();
        } else {
            writeFailureDropped.increment();
        }
    }

    private void incrementDropped(BufferReason reason, int rows) {
        if (rows <= 0) {
            return;
        }
        if (reason == BufferReason.REJECTION) {
            rejectedDropped.add(rows);
        } else {
            writeFailureDropped.add(rows);
        }
    }

    private void incrementDisabled(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedDisabled.increment();
        } else {
            writeFailureDisabled.increment();
        }
    }

    private void incrementDisabled(BufferReason reason, int rows) {
        if (rows <= 0) {
            return;
        }
        if (reason == BufferReason.REJECTION) {
            rejectedDisabled.add(rows);
        } else {
            writeFailureDisabled.add(rows);
        }
    }

    private int replayBatchesAllowedByLivePressure() {
        LivePressureSnapshot pressure = livePressureSnapshot();
        int maxBatches = Math.max(1, properties.getReplayMaxBatchesPerCycle());
        int limitedBatches = Math.max(1, properties.getReplayLimitedBatchesPerCycle());
        double utilizationPercent = pressure.queueUtilization() * 100D;
        if (utilizationPercent >= Math.max(0, properties.getReplayLiveQueuePauseThresholdPercent())) {
            replayPausedForLivePressureCount.increment();
            return 0;
        }
        if (utilizationPercent >= Math.max(0, properties.getReplayLiveQueueLimitedThresholdPercent())) {
            return Math.min(maxBatches, limitedBatches);
        }
        return maxBatches;
    }

    private LivePressureSnapshot livePressureSnapshot() {
        Supplier<LivePressureSnapshot> supplier = livePressureSupplier;
        if (supplier != null) {
            return supplier.get();
        }
        if (historyBatchWriterProvider == null) {
            return LivePressureSnapshot.none();
        }
        HistoryBatchWriter writer = historyBatchWriterProvider.getIfAvailable();
        if (writer == null) {
            return LivePressureSnapshot.none();
        }
        HistoryBatchMetrics metrics = writer.metrics();
        int capacity = Math.max(1, writer.flushExecutorQueueCapacity());
        return new LivePressureSnapshot(
                Math.max(0D, (double) metrics.flushExecutorQueueCurrent() / capacity));
    }

    private void recordReplayBatch(int size, long nanos) {
        replayBatchCount.increment();
        recordIntSample(replayBatchSizeSamples, size);
        recordLongSample(replayBatchWriteLatencyNanos, nanos);
    }

    private void recordBatchFallbackLatency(long nanos) {
        recordLongSample(batchFallbackLatencyNanos, nanos);
    }

    private void recordIntSample(List<Integer> samples, int value) {
        synchronized (samples) {
            if (samples.size() < METRIC_SAMPLE_LIMIT) {
                samples.add(value);
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

    private double replayAverageBatchSize() {
        long batches = replayBatchCount.sum();
        return batches <= 0L ? 0D : (double) replaySuccessfulRows.sum() / batches;
    }

    private double replayRowsPerSecond() {
        long rows = replaySuccessfulRows.sum();
        long elapsedNanos = Math.max(1L, System.nanoTime() - createdAtNanos);
        return rows * 1_000_000_000D / elapsedNanos;
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

    void livePressureSupplierForTest(Supplier<LivePressureSnapshot> livePressureSupplier) {
        this.livePressureSupplier = livePressureSupplier;
    }

    record LivePressureSnapshot(double queueUtilization) {
        private static LivePressureSnapshot none() {
            return new LivePressureSnapshot(0D);
        }
    }

    private enum BufferReason {
        WRITE_FAILURE("TDengine 写入失败"),
        REJECTION("History stage 执行器过载");

        private final String message;

        BufferReason(String message) {
            this.message = message;
        }

        private String message() {
            return message;
        }
    }
}
