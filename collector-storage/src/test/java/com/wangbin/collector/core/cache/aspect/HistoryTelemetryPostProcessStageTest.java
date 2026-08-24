package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.port.HistoryTelemetrySink;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryTelemetryPostProcessStageTest {

    @Test
    void enabledHistorySinkShouldSavePoint() {
        HistoryTelemetrySink historyTelemetrySink = mock(HistoryTelemetrySink.class);
        when(historyTelemetrySink.isEnabled()).thenReturn(true);
        HistoryTelemetryPostProcessStage stage = new HistoryTelemetryPostProcessStage(historyTelemetrySink);
        DataPoint point = point();
        ProcessResult processResult = ProcessResult.success(12.5d, 12.5d, "ok");
        TelemetryPostProcessContext context =
                new TelemetryPostProcessContext("dev-1", point, processResult, 12.5d, 123L, 1L);

        assertTrue(stage.enabled(context));
        stage.process(context);

        verify(historyTelemetrySink).savePoint("dev-1", point, processResult);
    }

    @Test
    void disabledHistorySinkShouldDisableStage() {
        HistoryTelemetrySink historyTelemetrySink = mock(HistoryTelemetrySink.class);
        when(historyTelemetrySink.isEnabled()).thenReturn(false);
        HistoryTelemetryPostProcessStage stage = new HistoryTelemetryPostProcessStage(historyTelemetrySink);

        assertFalse(stage.enabled(new TelemetryPostProcessContext(
                "dev-1", point(), ProcessResult.success(1, 1, "ok"), 1, 123L, 1L)));
    }

    private DataPoint point() {
        DataPoint point = new DataPoint();
        point.setDeviceId("dev-1");
        point.setPointId("p1");
        point.setStatus(1);
        return point;
    }
}
