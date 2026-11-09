package com.wangbin.collector.storage.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryRedisCapacityTest {

    @Test
    void fullRedisQueueFallsBackToBoundedLocalQueueAndCountsDrop() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.size(any())).thenReturn(1L);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 0L, 0L);
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setRedisQueueCapacity(1);
        properties.setLocalQueueCapacity(1);
        HistoryWriteBuffer buffer = new HistoryWriteBuffer(
                mock(TimeSeriesService.class), redis, new ObjectMapper(), properties);

        assertEquals(HistoryBufferOutcome.REDIS_BUFFERED, buffer.deferForRetry(request(1), null));
        assertEquals(HistoryBufferOutcome.LOCAL_BUFFERED, buffer.deferForRetry(request(2), null));
        assertEquals(HistoryBufferOutcome.DROPPED, buffer.deferForRetry(request(3), null));
        assertEquals(1L, buffer.metrics().rejectedRedisBuffered());
        assertEquals(1L, buffer.metrics().rejectedLocalBuffered());
        assertEquals(1L, buffer.metrics().rejectedDropped());
        assertEquals(1, buffer.metrics().localPending());
    }

    @Test
    void batchCapacityRejectionMustNotPartiallyPushRows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.size(any())).thenReturn(0L);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setRedisQueueCapacity(1);
        properties.setLocalQueueCapacity(1);
        HistoryWriteBuffer buffer = new HistoryWriteBuffer(
                mock(TimeSeriesService.class), redis, new ObjectMapper(), properties);

        HistoryBatchWriteResult result = buffer.deferBatchForRetry(
                List.of(request(1), request(2)), new IllegalStateException("rejected"));
        assertEquals(0, result.redisBufferedRows());
        assertEquals(1, result.localBufferedRows());
        assertEquals(1, result.droppedRows());
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private HistoryWriteRequest request(int index) {
        DataPoint point = new DataPoint();
        point.setPointId("p" + index);
        return new HistoryWriteRequest("dev", "MODBUS_TCP", point,
                ProcessResult.success(index, index), 1_000L + index);
    }
}
