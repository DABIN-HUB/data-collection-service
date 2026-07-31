package com.wangbin.collector.storage.buffer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.wangbin.collector.storage.service.TimeSeriesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * TDengine失败写入缓冲器，Redis队列承担跨重启恢复，本地队列承担Redis故障降级。
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

    /**
     * 创建当前组件实例。
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
     * 写入或持久化业务数据。
     */
    public void writeOrBuffer(HistoryWriteRequest request) {
        try {
            write(request);
        } catch (RuntimeException exception) {
            if (!properties.isEnabled()) {
                throw exception;
            }
            buffer(request, exception);
        }
    }

    /**
     * 优先回放Redis跨重启队列，再处理Redis故障期间形成的本地队列。
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
     * 处理组件生命周期。
     */
    @PreDestroy
    public void shutdown() {
        replay();
    }

    /**
     * 执行当前业务逻辑。
     */
    public HistoryBufferMetrics metrics() {
        try {
            return new HistoryBufferMetrics(
                    listSize(properties.getPendingKey()),
                    listSize(properties.getProcessingKey()),
                    listSize(properties.getDeadLetterKey()),
                    localQueue.size(),
                    properties.getLocalQueueCapacity());
        } catch (RuntimeException exception) {
            return new HistoryBufferMetrics(
                    -1L, -1L, -1L, localQueue.size(), properties.getLocalQueueCapacity());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void buffer(HistoryWriteRequest request, RuntimeException writeException) {
        try {
            redisTemplate.opsForList().leftPush(properties.getPendingKey(), serialize(request));
            log.warn("TDengine写入失败，数据已进入Redis待写队列: 设备={}, 点位={}",
                    request.getDeviceId(), pointId(request), writeException);
        } catch (RuntimeException redisException) {
            if (!localQueue.offer(request)) {
                log.error("历史数据本地降级队列已满，无法继续缓冲: 设备={}, 点位={}",
                        request.getDeviceId(), pointId(request), redisException);
                return;
            }
            log.warn("TDengine和Redis同时不可用，数据已进入本地降级队列: 设备={}, 点位={}",
                    request.getDeviceId(), pointId(request), redisException);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void replayRedisQueue() {
        int batchSize = Math.max(1, properties.getReplayBatchSize());
        for (int index = 0; index < batchSize; index++) {
            String json;
            try {
                json = currentOrClaim();
            } catch (RuntimeException exception) {
                log.warn("读取历史数据Redis待写队列失败", exception);
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
                log.warn("历史数据回放失败，将在下一周期继续重试", exception);
                return;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String currentOrClaim() {
        String processing = redisTemplate.opsForList().index(properties.getProcessingKey(), 0L);
        if (processing != null) {
            return processing;
        }
        return redisTemplate.opsForList().rightPopAndLeftPush(
                properties.getPendingKey(), properties.getProcessingKey());
    }

    /**
     * 执行当前业务逻辑。
     */
    private void moveToDeadLetter(String json, Exception exception) {
        try {
            redisTemplate.opsForList().remove(properties.getProcessingKey(), 1L, json);
            redisTemplate.opsForList().leftPush(properties.getDeadLetterKey(), json);
        } catch (RuntimeException redisException) {
            exception.addSuppressed(redisException);
        }
        log.error("历史数据待写消息无法反序列化，已转入隔离队列", exception);
    }

    /**
     * 执行当前业务逻辑。
     */
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
                log.warn("历史数据本地降级队列回放失败，将在下一周期继续重试", exception);
                return;
            }
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void write(HistoryWriteRequest request) {
        timeSeriesService.append(
                request.getDeviceId(),
                request.getProtocolType(),
                request.getPoint(),
                request.getProcessResult(),
                request.getEventTs());
    }

    /**
     * 执行当前业务逻辑。
     */
    private String serialize(HistoryWriteRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化历史数据待写消息失败", exception);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private HistoryWriteRequest deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, HistoryWriteRequest.class);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String pointId(HistoryWriteRequest request) {
        return request.getPoint() == null ? null : request.getPoint().getPointId();
    }

    /**
     * 查询并返回业务数据。
     */
    private long listSize(String key) {
        Long size = redisTemplate.opsForList().size(key);
        return size == null ? 0L : size;
    }
}
