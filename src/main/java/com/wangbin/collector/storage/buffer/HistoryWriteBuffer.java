package com.wangbin.collector.storage.buffer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.logging.RateLimitedLogReporter;
import com.wangbin.collector.storage.service.TimeSeriesService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

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
    private final BlockingQueue<HistoryWriteRequest> localQueue;
    private final RateLimitedLogReporter overloadLogReporter = new RateLimitedLogReporter(log);
    private final LongAdder writeFailureRedisBuffered = new LongAdder();
    private final LongAdder rejectedRedisBuffered = new LongAdder();
    private final LongAdder writeFailureLocalBuffered = new LongAdder();
    private final LongAdder rejectedLocalBuffered = new LongAdder();
    private final LongAdder writeFailureDropped = new LongAdder();
    private final LongAdder rejectedDropped = new LongAdder();
    private final LongAdder writeFailureDisabled = new LongAdder();
    private final LongAdder rejectedDisabled = new LongAdder();

    /**
     * 创建历史写入缓冲器。
     */
    public HistoryWriteBuffer(TimeSeriesService timeSeriesService,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              HistoryBufferProperties properties) {
        this.timeSeriesService = timeSeriesService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        this.properties = properties;
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
            int redisBuffered = 0;
            int localBuffered = 0;
            int dropped = 0;
            int disabled = 0;
            for (HistoryWriteRequest request : requests) {
                if (request != null) {
                    HistoryBufferOutcome outcome = buffer(request, exception, BufferReason.WRITE_FAILURE);
                    switch (outcome) {
                        case REDIS_BUFFERED -> redisBuffered++;
                        case LOCAL_BUFFERED -> localBuffered++;
                        case DROPPED -> dropped++;
                        case DISABLED -> disabled++;
                    }
                }
            }
            return new HistoryBatchWriteResult(false, rows, redisBuffered, localBuffered, dropped, disabled);
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
     * 优先回放 Redis 处理队列，再处理 Redis 故障期间形成的本地队列。
     */
    @Scheduled(fixedDelayString = "${telemetry.tdengine.buffer.replay-interval-ms:3000}")
    public void replay() {
        if (!properties.isEnabled()) {
            return;
        }
        replayRedisQueue();
        replayLocalQueue();
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
                rejectedDisabled.sum());
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

    private void replayRedisQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int index = 0; index < batchSize; index++) {
            String json;
            try {
                json = currentOrClaim();
            } catch (RuntimeException exception) {
                log.warn("读取历史数据 Redis 待写队列失败", exception);
                return;
            }
            if (json == null) {
                return;
            }
            try {
                write(deserialize(json));
                redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
            } catch (JsonProcessingException exception) {
                moveToDeadLetter(json, exception);
            } catch (RuntimeException exception) {
                log.warn("历史数据回放失败，将在下一个周期继续重试", exception);
                return;
            }
        }
    }

    private String currentOrClaim() {
        String processing = redisTemplate.opsForList().index(properties.getProcessingKey(), 0L);
        if (processing != null) {
            return processing;
        }
        return redisTemplate.opsForList().rightPopAndLeftPush(
                properties.getPendingKey(), properties.getProcessingKey());
    }

    private void moveToDeadLetter(String json, Exception exception) {
        try {
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
            redisTemplate.opsForList().leftPush(properties.getDeadLetterKey(), json);
        } catch (RuntimeException redisException) {
            exception.addSuppressed(redisException);
        }
        log.error("历史数据待写消息无法反序列化，已转入隔离队列", exception);
    }

    private void replayLocalQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int index = 0; index < batchSize; index++) {
            HistoryWriteRequest request = localQueue.peek();
            if (request == null) {
                return;
            }
            try {
                write(request);
                localQueue.poll();
            } catch (RuntimeException exception) {
                log.warn("历史数据本地降级队列回放失败，将在下一个周期继续重试", exception);
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

    private void incrementLocalBuffered(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedLocalBuffered.increment();
        } else {
            writeFailureLocalBuffered.increment();
        }
    }

    private void incrementDropped(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedDropped.increment();
        } else {
            writeFailureDropped.increment();
        }
    }

    private void incrementDisabled(BufferReason reason) {
        if (reason == BufferReason.REJECTION) {
            rejectedDisabled.increment();
        } else {
            writeFailureDisabled.increment();
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
