package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.service.CacheReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollectorDataPostProcessorTest {

    @Test
    void saveBatchAsyncShouldReportNormalizedResultWhenCollectorHasNoProcessResult() {
        CollectorDataPostProcessor processor = new CollectorDataPostProcessor();
        MultiLevelCacheManager multiLevelCacheManager = mock(MultiLevelCacheManager.class);
        CacheReportService cacheReportService = mock(CacheReportService.class);
        TelemetryStreamService telemetryStreamService = mock(TelemetryStreamService.class);
        ReflectionTestUtils.setField(processor, "multiLevelCacheManager", multiLevelCacheManager);
        ReflectionTestUtils.setField(processor, "cacheReportService", cacheReportService);
        ReflectionTestUtils.setField(processor, "telemetryStreamService", telemetryStreamService);

        DataPoint point = new DataPoint();
        point.setDeviceId("dev-1");
        point.setPointId("p1");
        point.setPointName("point-1");
        point.setCacheEnabled(1);

        processor.saveBatchAsync("dev-1", List.of(point), Map.of("p1", 12.5), null);

        verify(multiLevelCacheManager).put(any(CacheKey.class), any(), anyLong());
        ArgumentCaptor<Object> reportValue = ArgumentCaptor.forClass(Object.class);
        verify(cacheReportService).reportPoint(
                eq("dev-1"),
                eq(MessageConstant.MESSAGE_TYPE_PROPERTY_POST),
                eq(point),
                reportValue.capture()
        );

        ProcessResult result = assertInstanceOf(ProcessResult.class, reportValue.getValue());
        assertEquals(12.5, result.getFinalValue());
    }
}
