package com.wangbin.collector.core.cache.ingress;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.logging.RateLimitedLogReporter;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

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
    private final RuntimeInstanceIdentity runtimeInstanceIdentity;
    private final BlockingQueue<TelemetryIngressEnvelope> localQueue;
    private final AtomicBoolean redisReplayActive = new AtomicBoolean();
    private final AtomicBoolean localReplayActive = new AtomicBoolean();
    private final RateLimitedLogReporter overloadLogReporter = new RateLimitedLogReporter(log);
    private final LongAdder rejectedTasks = new LongAdder();
    private final LongAdder rejectedItems = new LongAdder();
    private final LongAdder redisBufferedItems = new LongAdder();
    private final LongAdder localBufferedItems = new LongAdder();
    private final LongAdder droppedItems = new LongAdder();
    private final LongAdder replayCompletedItems = new LongAdder();
    private final LongAdder pendingRemoveFailures = new LongAdder();
    private final LongAdder poisonDeadLetterItems = new LongAdder();
    private final LongAdder staleSameRuntimeDroppedItems = new LongAdder();
    private final LongAdder crossRuntimeRecoveredItems = new LongAdder();
    private final LongAdder legacyEnvelopeRecoveredItems = new LongAdder();

    /**
     * 创建遥测入口缓冲。
     */
    public RedisTelemetryIngressBuffer(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       TelemetryIngressBufferProperties properties,
                                       TelemetryPostProcessPipeline pipeline,
                                       CollectionTaskGuard collectionTaskGuard,
                                       RuntimeInstanceIdentity runtimeInstanceIdentity) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        this.properties = properties;
        this.pipeline = pipeline;
        this.collectionTaskGuard = collectionTaskGuard;
        this.runtimeInstanceIdentity = java.util.Objects.requireNonNull(
                runtimeInstanceIdentity, "runtimeInstanceIdentity must not be null");
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
            overloadLogReporter.warn("entry-redis-buffered",
                    "遥测入口执行器过载，遥测已进入 Redis 入口待处理队列，条数={}，原因={}",
                    itemCount, failureMessage(cause));
            return new TelemetryIngressBufferResult(itemCount, itemCount, 0, 0);
        } catch (RuntimeException exception) {
            return deferToLocal(envelopes, exception);
        }
    }

    @Override
    public void recordDropped(int itemCount, RuntimeException cause) {
        if (itemCount <= 0) {
            return;
        }
        droppedItems.add(itemCount);
        log.error("遥测入口过载补偿自身异常，数据被显式丢弃，条数={}，原因={}",
                itemCount, failureMessage(cause));
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
        log.debug("遥测入口持久待处理队列在停机时保留给下一运行实例恢复，pendingKey={}", properties.getPendingKey());
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
                poisonDeadLetterItems.sum(),
                staleSameRuntimeDroppedItems.sum(),
                crossRuntimeRecoveredItems.sum(),
                legacyEnvelopeRecoveredItems.sum());
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
            envelopes.add(TelemetryIngressEnvelope.from(context, runtimeInstanceIdentity.runtimeId()));
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
            overloadLogReporter.warn("entry-local-buffered",
                    "Redis 入口待处理队列不可用，遥测已进入本地有界入口队列，条数={}，原因={}",
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
        if (redisReplayActive.compareAndSet(false, true)) replayRedisNext(Math.max(1, properties.getReplayBatchSize()));
    }

    private void replayRedisNext(int remaining) {
        if (remaining <= 0) {
            redisReplayActive.set(false);
            return;
        }
        String json;
        try {
            json = currentOrClaim();
        } catch (RuntimeException exception) {
            redisReplayActive.set(false);
            log.warn("读取遥测入口 Redis 待处理队列失败", exception);
            return;
        }
        if (json == null) {
            redisReplayActive.set(false);
            return;
        }
        try {
            replayOne(deserialize(json)).whenComplete((ignored, error) -> {
                if (error != null) {
                    redisReplayActive.set(false);
                    log.warn("遥测可靠补偿尚未完成，保留待处理消息", error);
                    return;
                }
                try {
                    removeProcessing(json);
                    replayCompletedItems.increment();
                    replayRedisNext(remaining - 1);
                } catch (RuntimeException exception) {
                    redisReplayActive.set(false);
                    log.warn("遥测补偿确认移除失败，保留重试", exception);
                }
            });
        } catch (JsonProcessingException exception) {
            moveToDeadLetter(json, exception);
            redisReplayActive.set(false);
        } catch (RuntimeException exception) {
            redisReplayActive.set(false);
            log.warn("遥测入口缓冲回放失败，保留下一周期重试", exception);
        }
    }

    private void replayLocalQueue() {
        if (localReplayActive.compareAndSet(false, true)) replayLocalNext(Math.max(1, properties.getReplayBatchSize()));
    }

    private void replayLocalNext(int remaining) {
        if (remaining <= 0) {
            localReplayActive.set(false);
            return;
        }
        TelemetryIngressEnvelope envelope = localQueue.peek();
        if (envelope == null) {
            localReplayActive.set(false);
            return;
        }
        try {
            replayOne(envelope).whenComplete((ignored, error) -> {
                if (error != null) {
                    localReplayActive.set(false);
                    log.warn("遥测入口本地补偿尚未完成，保留消息", error);
                    return;
                }
                localQueue.poll();
                replayCompletedItems.increment();
                replayLocalNext(remaining - 1);
            });
        } catch (RuntimeException exception) {
            localReplayActive.set(false);
            log.warn("遥测入口本地回放失败，保留下一周期重试", exception);
        }
    }

    private CompletableFuture<Void> replayOne(TelemetryIngressEnvelope envelope) {
        if (!shouldReplay(envelope)) {
            droppedItems.increment();
            return CompletableFuture.completedFuture(null);
        }
        return pipeline.processRecovery(envelope.toContext(collectionTaskGuard));
    }

    private boolean shouldReplay(TelemetryIngressEnvelope envelope) {
        Long generation = envelope.generation();
        if (generation == null) {
            return true;
        }
        String ownerRuntimeId = envelope.runtimeId();
        if (ownerRuntimeId == null) {
            legacyEnvelopeRecoveredItems.increment();
            return true;
        }
        if (!ownerRuntimeId.equals(runtimeInstanceIdentity.runtimeId())) {
            crossRuntimeRecoveredItems.increment();
            return true;
        }
        boolean current = collectionTaskGuard.isCurrent(envelope.deviceId(), generation);
        if (!current) {
            staleSameRuntimeDroppedItems.increment();
        }
        // 同进程旧代次沿用明确丢弃策略；跨进程与旧格式消息仍只恢复历史和可靠上报。
        return current;
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
