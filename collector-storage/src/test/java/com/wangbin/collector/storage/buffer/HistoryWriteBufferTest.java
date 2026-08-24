package com.wangbin.collector.storage.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HistoryWriteBufferTest {

    @Test
    void shouldBufferFailedWriteAndReplayProcessingMessage() throws Exception {
        TimeSeriesService failedService = mock(TimeSeriesService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        HistoryBufferProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper();
        HistoryWriteBuffer failedBuffer = new HistoryWriteBuffer(
                failedService, redisTemplate, objectMapper, properties);
        HistoryWriteRequest request = request();
        doThrow(new IllegalStateException("TDengine不可用"))
                .when(failedService).append(any(), any(), any(), any(), eq(1_000L));

        failedBuffer.writeOrBuffer(request);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq(properties.getPendingKey()), jsonCaptor.capture());

        TimeSeriesService recoveredService = mock(TimeSeriesService.class);
        String json = jsonCaptor.getValue();
        when(listOperations.range(properties.getProcessingKey(), 0L, 1L)).thenReturn(java.util.List.of(json));
        when(listOperations.rightPopAndLeftPush(
                properties.getPendingKey(), properties.getProcessingKey())).thenReturn(null);
        HistoryWriteBuffer recoveredBuffer = new HistoryWriteBuffer(
                recoveredService, redisTemplate, objectMapper, properties);

        recoveredBuffer.replay();

        verify(recoveredService).appendBatch(any());
        verify(recoveredService, never()).append(any(), any(), any(), any(), anyLong());
        verify(listOperations).remove(properties.getProcessingKey(), 1L, json);
    }

    private HistoryBufferProperties properties() {
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setPendingKey("history:pending");
        properties.setProcessingKey("history:processing");
        properties.setDeadLetterKey("history:dead");
        properties.setReplayBatchSize(2);
        properties.setReplayMaxBatchesPerCycle(1);
        return properties;
    }

    private HistoryWriteRequest request() {
        DataPoint point = new DataPoint();
        point.setPointId("temperature");
        ProcessResult result = ProcessResult.success(20D, 20D, "处理成功");
        return new HistoryWriteRequest("device-1", "MODBUS_TCP", point, result, 1_000L);
    }
}
