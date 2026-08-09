package com.wangbin.collector.storage.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.buffer.HistoryBatchWriter;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import com.wangbin.collector.storage.config.TdengineProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryDataServiceTest {

    @Test
    void batchAcceptedShouldNotUseSingleWritePath() {
        HistoryWriteBuffer writeBuffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter batchWriter = mock(HistoryBatchWriter.class);
        when(batchWriter.accept(any())).thenReturn(true);
        HistoryDataService service = service(writeBuffer, batchWriter);

        service.savePoint("dev-1", point("p1"), ProcessResult.success(1, 1));

        verify(batchWriter).accept(any());
        verify(writeBuffer, never()).writeOrBuffer(any());
    }

    @Test
    void batchDisabledShouldUseExistingSingleWritePath() {
        HistoryWriteBuffer writeBuffer = mock(HistoryWriteBuffer.class);
        HistoryBatchWriter batchWriter = mock(HistoryBatchWriter.class);
        when(batchWriter.accept(any())).thenReturn(false);
        HistoryDataService service = service(writeBuffer, batchWriter);

        service.savePoint("dev-1", point("p1"), ProcessResult.success(1, 1));

        ArgumentCaptor<HistoryWriteRequest> captor = ArgumentCaptor.forClass(HistoryWriteRequest.class);
        verify(writeBuffer).writeOrBuffer(captor.capture());
        assertTrue(captor.getValue().getEventTs() > 0L);
    }

    private HistoryDataService service(HistoryWriteBuffer writeBuffer, HistoryBatchWriter batchWriter) {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        return new HistoryDataService(
                writeBuffer,
                batchWriter,
                mock(TimeSeriesService.class),
                mock(ConfigManager.class),
                properties);
    }

    private DataPoint point(String pointId) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return point;
    }
}
