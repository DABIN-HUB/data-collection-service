package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.BatchPointWriteResponse;
import com.wangbin.collector.api.controller.dto.DeviceCommandRequest;
import com.wangbin.collector.api.controller.dto.DeviceCommandResponse;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.api.controller.dto.PointWriteResultResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlControllerTest {

    private final ConfigManager configManager = mock(ConfigManager.class);
    private final CollectionManager collectionManager = mock(CollectionManager.class);
    private final ControlController controller = new ControlController(
            configManager, collectionManager, new DevicePointResolver(configManager));

    @Test
    void shouldWriteSinglePoint() {
        DataPoint point = point("p1", "temperature", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(collectionManager.writePoint("dev-1", point, 25)).thenReturn(true);

        PointWriteRequest request = new PointWriteRequest();
        request.setValue(25);

        ApiResult<PointWriteResultResponse> result = controller.writePoint("dev-1", "temperature", request);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getData().getSuccess());
        verify(collectionManager).writePoint("dev-1", point, 25);
    }

    @Test
    void shouldWriteBatchPointsByReportField() {
        DataPoint point = point("p1", "temperature", "RW");
        point.setAdditionalConfig(Map.of("reportField", "temp_report"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(collectionManager.writePoints(eq("dev-1"), anyMap())).thenReturn(Map.of("p1", true));

        PointWriteRequest request = new PointWriteRequest();
        request.setValues(new LinkedHashMap<>(Map.of("temp_report", 25)));

        ApiResult<BatchPointWriteResponse> result = controller.writePoints("dev-1", request);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getData().getFields().get("temp_report").getSuccess());
        verify(collectionManager).writePoints(eq("dev-1"), anyMap());
    }

    @Test
    void shouldRejectReadOnlyPointBeforeProtocolWrite() {
        DataPoint point = point("p1", "temperature", "R");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        PointWriteRequest request = new PointWriteRequest();
        request.setValues(new LinkedHashMap<>(Map.of("temperature", 25)));

        ApiResult<BatchPointWriteResponse> result = controller.writePoints("dev-1", request);

        assertEquals(1004, result.getCode());
        assertEquals("点位不可写", result.getData().getFields().get("temperature").getError());
        verify(collectionManager, never()).writePoints(eq("dev-1"), anyMap());
    }

    @Test
    void shouldExecuteCommand() {
        DeviceCommandRequest request = new DeviceCommandRequest();
        request.setCommand("browse");
        request.setParams(Map.of("depth", 1));
        when(collectionManager.executeCommand("dev-1", "browse", request.getParams()))
                .thenReturn(Map.of("accepted", true));

        ApiResult<DeviceCommandResponse> result = controller.executeCommand("dev-1", request);

        assertEquals(200, result.getCode());
        assertTrue(((Map<?, ?>) result.getData().getResult()).containsKey("accepted"));
        verify(collectionManager).executeCommand("dev-1", "browse", request.getParams());
    }

    private DataPoint point(String pointId, String pointCode, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setReadWrite(readWrite);
        point.setStatus(1);
        return point;
    }
}