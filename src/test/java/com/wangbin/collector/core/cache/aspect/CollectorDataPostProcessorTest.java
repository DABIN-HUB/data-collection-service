package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.service.CacheReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class CollectorDataPostProcessorTest {

    @Test
    void saveBatchAsyncShouldReportNormalizedResultWhenCollectorHasNoProcessResult() {
        MultiLevelCacheManager multiLevelCacheManager = mock(MultiLevelCacheManager.class);
        CacheReportService cacheReportService = mock(CacheReportService.class);
        TelemetryStreamService telemetryStreamService = mock(TelemetryStreamService.class);
        CollectorDataPostProcessor processor = createProcessor(
                Runnable::run,
                multiLevelCacheManager,
                cacheReportService,
                telemetryStreamService,
                true
        );

        DataPoint point = createPoint("dev-1", "p1");
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

    @Test
    void savePointAsyncShouldKeepStreamAndReportWhenCacheDisabled() {
        MultiLevelCacheManager multiLevelCacheManager = mock(MultiLevelCacheManager.class);
        CacheReportService cacheReportService = mock(CacheReportService.class);
        TelemetryStreamService telemetryStreamService = mock(TelemetryStreamService.class);
        CollectorDataPostProcessor processor = createProcessor(
                Runnable::run,
                multiLevelCacheManager,
                cacheReportService,
                telemetryStreamService,
                true
        );

        DataPoint point = createPoint("dev-2", "p2");
        point.setCacheEnabled(0);
        point.getAdditionalConfig().put("reportEnabled", true);
        point.getAdditionalConfig().put("reportField", "temperature");

        processor.savePointAsync("dev-2", point, 21.5);

        verify(multiLevelCacheManager, never()).put(any(CacheKey.class), any(), anyLong());
        verify(telemetryStreamService).append(eq("dev-2"), eq(point), any(ProcessResult.class));
        verify(cacheReportService).reportPoint(
                eq("dev-2"),
                eq(MessageConstant.MESSAGE_TYPE_PROPERTY_POST),
                eq(point),
                any(ProcessResult.class)
        );
    }

    @Test
    void savePointAsyncShouldDropQueuedTaskWhenGenerationBecomesStale() {
        class QueuingExecutor implements Executor {
            private Runnable pending;

            @Override
            public void execute(Runnable command) {
                pending = command;
            }

            void runPending() {
                if (pending == null) {
                    return;
                }
                Runnable task = pending;
                pending = null;
                task.run();
            }
        }

        MultiLevelCacheManager multiLevelCacheManager = mock(MultiLevelCacheManager.class);
        CacheReportService cacheReportService = mock(CacheReportService.class);
        TelemetryStreamService telemetryStreamService = mock(TelemetryStreamService.class);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        QueuingExecutor executor = new QueuingExecutor();
        CollectorDataPostProcessor processor = createProcessor(
                executor,
                multiLevelCacheManager,
                cacheReportService,
                telemetryStreamService,
                true,
                guard
        );

        DataPoint point = createPoint("dev-3", "p3");
        point.setCacheEnabled(1);
        long generation = guard.activateNextGeneration("dev-3");

        guard.runWithContext("dev-3", generation, () -> processor.savePointAsync("dev-3", point, 88));
        guard.clearDevice("dev-3");
        executor.runPending();

        verify(multiLevelCacheManager, never()).put(any(CacheKey.class), any(), anyLong());
        verify(telemetryStreamService, never()).append(eq("dev-3"), eq(point), any(ProcessResult.class));
        verify(cacheReportService, never()).reportPoint(
                eq("dev-3"),
                eq(MessageConstant.MESSAGE_TYPE_PROPERTY_POST),
                eq(point),
                any(ProcessResult.class)
        );
    }

    private CollectorDataPostProcessor createProcessor(Executor executor,
                                                       MultiLevelCacheManager multiLevelCacheManager,
                                                       CacheReportService cacheReportService,
                                                       TelemetryStreamService telemetryStreamService,
                                                       boolean streamEnabled) {
        return createProcessor(
                executor,
                multiLevelCacheManager,
                cacheReportService,
                telemetryStreamService,
                streamEnabled,
                new CollectionTaskGuard()
        );
    }

    private CollectorDataPostProcessor createProcessor(Executor executor,
                                                       MultiLevelCacheManager multiLevelCacheManager,
                                                       CacheReportService cacheReportService,
                                                       TelemetryStreamService telemetryStreamService,
                                                       boolean streamEnabled,
                                                       CollectionTaskGuard collectionTaskGuard) {
        TelemetryStreamProperties streamProperties = new TelemetryStreamProperties();
        streamProperties.setEnabled(streamEnabled);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(
                new CacheTelemetryPostProcessStage(multiLevelCacheManager),
                new StreamTelemetryPostProcessStage(telemetryStreamService, streamProperties),
                new ReportTelemetryPostProcessStage(cacheReportService)
        ));
        return new CollectorDataPostProcessor(executor, pipeline, collectionTaskGuard);
    }

    private DataPoint createPoint(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        point.setAdditionalConfig(new java.util.HashMap<>());
        return point;
    }
}
