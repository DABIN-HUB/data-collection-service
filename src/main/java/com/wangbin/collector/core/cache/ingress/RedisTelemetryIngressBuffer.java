package com.wangbin.collector.core.cache.ingress;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 基于 Redis pending 与本地有界队列的遥测入口过载缓冲。
 */
@Slf4j
@Component
public class RedisTelemetryIngressBuffer implements TelemetryIngressBuffer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TelemetryIngressBufferProperties properties;
    private final TelemetryPostProcessPipeline pipeline;
    private final CollectionTaskGuard collectionTaskGuard;
    private final BlockingQueue<TelemetryIngressEnvelope> localQueue;
    private final LongAdder rejectedTasks = new LongAdder();
    private final LongAdder rejectedItems = new LongAdder();
    private final LongAdder redisBufferedItems = new LongAdder();
    private final LongAdder localBufferedItems = new LongAdder();
    private final LongAdder droppedItems = new LongAdder();
    private final LongAdder replayCompletedItems = new LongAdder();
    private final LongAdder pendingRemoveFailures = new LongAdder();
    private final LongAdder poisonDeadLetterItems = new LongAdder();

    /**
     * 创建遥测入口缓冲。
     */
    public RedisTelemetryIngressBuffer(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       TelemetryIngressBufferProperties properties,
                                       TelemetryPostProcessPipeline pipeline,
                                       CollectionTaskGuard collectionTaskGuard) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        this.properties = properties;
        this.pipeline = pipeline;
        this.collectionTaskGuard = collectionTaskGuard;
        this.localQueue = new ArrayBlockingQueue<>(Math.max(1, properties.getLocalQueueCapacity()));
    }

    @Override
    public TelemetryIngressBufferResult defer(List<TelemetryPostProcessContext> contexts, RuntimeException cause) {
        List<TelemetryIngressEnvelope> envelopes = toEnvelopes(contexts);
        int itemCount = envelopes.size();
        if (itemCount == 0) {
            return new TelemetryIngressBufferResult(0, 0, 0, 0);
        }
        rejectedTasks.increment();
        rejectedItems.add(itemCount);
        if (!properties.isEnabled()) {
            droppedItems.add(itemCount);
            log.error("遥测入口缓冲未启用，过载遥测被明确丢弃，条数={}，原因={}",
                    itemCount, failureMessage(cause));
            return new TelemetryIngressBufferResult(itemCount, 0, 0, itemCount);
        }
        try {
            List<String> payloads = new ArrayList<>(itemCount);
            for (TelemetryIngressEnvelope envelope : envelopes) {
                payloads.add(serialize(envelope));
            }
            redisTemplate.opsForList().leftPushAll(properties.getPendingKey(), payloads);
            redisBufferedItems.add(itemCount);
            log.warn("遥测入口执行器过载，遥测已进入 Redis 入口待处理队列，条数={}，原因={}",
                    itemCount, failureMessage(cause));
            return new TelemetryIngressBufferResult(itemCount, itemCount, 0, 0);
        } catch (RuntimeException exception) {
            return deferToLocal(envelopes, exception);
        }
    }

    /**
     * 按固定节奏回放入口缓冲，避免过载后形成递归重试。
     */
    @Scheduled(fixedDelayString = "${collector.telemetry-ingress-buffer.replay-interval-ms:1000}")
    public void replay() {
        if (!properties.isEnabled()) {
            return;
        }
        replayRedisQueue();
        replayLocalQueue();
    }

    /**
     * 停机时按当前语义做一次有限回放。
     */
    @PreDestroy
    public void shutdown() {
        replay();
    }

    @Override
    public TelemetryIngressBufferMetrics metrics() {
        try {
            return newMetrics(
                    listSize(properties.getPendingKey()),
                    listSize(properties.getProcessingKey()),
                    listSize(properties.getDeadLetterKey()));
        } catch (RuntimeException exception) {
            return newMetrics(-1L, -1L, -1L);
        }
    }

    private TelemetryIngressBufferMetrics newMetrics(long redisPending,
                                                     long redisProcessing,
                                                     long redisDeadLetter) {
        return new TelemetryIngressBufferMetrics(
                redisPending,
                redisProcessing,
                redisDeadLetter,
                localQueue.size(),
                properties.getLocalQueueCapacity(),
                rejectedTasks.sum(),
                rejectedItems.sum(),
                redisBufferedItems.sum(),
                localBufferedItems.sum(),
                droppedItems.sum(),
                replayCompletedItems.sum(),
                pendingRemoveFailures.sum(),
                poisonDeadLetterItems.sum());
    }

    private List<TelemetryIngressEnvelope> toEnvelopes(List<TelemetryPostProcessContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        List<TelemetryIngressEnvelope> envelopes = new ArrayList<>(contexts.size());
        for (TelemetryPostProcessContext context : contexts) {
            if (context == null || context.deviceId() == null
                    || context.point() == null || context.processResult() == null) {
                continue;
            }
            envelopes.add(TelemetryIngressEnvelope.from(context));
        }
        return envelopes;
    }

    private TelemetryIngressBufferResult deferToLocal(List<TelemetryIngressEnvelope> envelopes, RuntimeException cause) {
        int local = 0;
        int dropped = 0;
        for (TelemetryIngressEnvelope envelope : envelopes) {
            if (localQueue.offer(envelope)) {
                local++;
            } else {
                dropped++;
            }
        }
        if (local > 0) {
            localBufferedItems.add(local);
            log.warn("Redis 入口待处理队列不可用，遥测已进入本地有界入口队列，条数={}，原因={}",
                    local, failureMessage(cause));
        }
        if (dropped > 0) {
            droppedItems.add(dropped);
            log.error("Redis 入口待处理队列不可用，且本地入口队列已满，遥测被明确丢弃，条数={}，原因={}",
                    dropped, failureMessage(cause));
        }
        return new TelemetryIngressBufferResult(envelopes.size(), 0, local, dropped);
    }

    private void replayRedisQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int index = 0; index < batchSize; index++) {
            String json;
            try {
                json = currentOrClaim();
            } catch (RuntimeException exception) {
                log.warn("读取遥测入口 Redis 待处理队列失败", exception);
                return;
            }
            if (json == null) {
                return;
            }
            try {
                replayOne(deserialize(json));
                removeProcessing(json);
            } catch (JsonProcessingException exception) {
                moveToDeadLetter(json, exception);
            } catch (RuntimeException exception) {
                log.warn("遥测入口缓冲回放失败，将在下一个周期继续重试", exception);
                return;
            }
        }
    }

    private void replayLocalQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int index = 0; index < batchSize; index++) {
            TelemetryIngressEnvelope envelope = localQueue.peek();
            if (envelope == null) {
                return;
            }
            try {
                replayOne(envelope);
                localQueue.poll();
            } catch (RuntimeException exception) {
                log.warn("遥测入口本地降级队列回放失败，将在下一个周期继续重试", exception);
                return;
            }
        }
    }

    private void replayOne(TelemetryIngressEnvelope envelope) {
        if (!isCurrent(envelope)) {
            droppedItems.increment();
            return;
        }
        pipeline.process(envelope.toContext());
        replayCompletedItems.increment();
    }

    private boolean isCurrent(TelemetryIngressEnvelope envelope) {
        Long generation = envelope.generation();
        if (generation == null) {
            return true;
        }
        return collectionTaskGuard.isCurrent(envelope.deviceId(), generation);
    }

    private String currentOrClaim() {
        String processing = redisTemplate.opsForList().index(properties.getProcessingKey(), 0L);
        if (processing != null) {
            return processing;
        }
        return redisTemplate.opsForList().rightPopAndLeftPush(
                properties.getPendingKey(), properties.getProcessingKey());
    }

    private void removeProcessing(String json) {
        try {
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
        } catch (RuntimeException exception) {
            pendingRemoveFailures.increment();
            throw exception;
        }
    }

    private void moveToDeadLetter(String json, Exception exception) {
        try {
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
            redisTemplate.opsForList().leftPush(properties.getDeadLetterKey(), json);
            poisonDeadLetterItems.increment();
        } catch (RuntimeException redisException) {
            exception.addSuppressed(redisException);
        }
        log.error("遥测入口待处理消息无法反序列化，已转入隔离队列", exception);
    }

    private String serialize(TelemetryIngressEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化遥测入口待处理消息失败", exception);
        }
    }

    private TelemetryIngressEnvelope deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, TelemetryIngressEnvelope.class);
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
}
