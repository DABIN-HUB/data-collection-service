package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.ControlCommandApplicationService;
import com.wangbin.collector.api.controller.dto.BatchPointWriteResponse;
import com.wangbin.collector.api.controller.dto.DeviceCommandRequest;
import com.wangbin.collector.api.controller.dto.DeviceCommandResponse;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.api.controller.dto.PointWriteResultResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlControllerTest {

    private final ConfigManager configManager = mock(ConfigManager.class);
    private final CollectionManager collectionManager = mock(CollectionManager.class);
    private final CollectionService collectionService = mock(CollectionService.class);
    private final ProtocolCollector collector = mock(ProtocolCollector.class);
    private final ControlCommandApplicationService applicationService = new ControlCommandApplicationService(
            configManager, collectionManager, new DevicePointResolver(configManager));
    private final ControlController controller = new ControlController(applicationService);

    @BeforeEach
    void setUp() {
        applicationService.setCollectionService(collectionService);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-1");
        when(configManager.getDevice("dev-1")).thenReturn(device);
        when(collectionService.isDeviceRunning("dev-1")).thenReturn(true);
        when(collectionManager.getCollector("dev-1")).thenReturn(collector);
        when(collectionManager.isDeviceConnected("dev-1")).thenReturn(true);
    }

    @Test
    void shouldWriteSinglePointAndReadBackWhenRw() {
        DataPoint point = point("p1", "temperature", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(collectionManager.writePoint("dev-1", point, 25)).thenReturn(true);
        when(collectionManager.readPoint("dev-1", point)).thenReturn(25);

        ApiResult<PointWriteResultResponse> result = controller.writePoint("dev-1", "temperature", singleWrite(25));

        assertEquals(200, result.getCode());
        assertTrue(result.getData().getSuccess());
        assertTrue(result.getData().getReadbackAttempted());
        assertTrue(result.getData().getReadbackSuccess());
        assertEquals(25, result.getData().getReadbackValue());
        verify(collectionManager).writePoint("dev-1", point, 25);
        verify(collectionManager).readPoint("dev-1", point);
    }

    @Test
    void shouldWriteBatchPointsByReportField() {
        DataPoint point = point("p1", "temperature", "RW");
        point.setAdditionalConfig(Map.of("reportField", "temp_report"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(collectionManager.writePoints(eq("dev-1"), anyMap())).thenReturn(Map.of("p1", true));

        ApiResult<BatchPointWriteResponse> result = controller.writePoints("dev-1", batchWrite("temp_report", 25));

        assertEquals(200, result.getCode());
        assertTrue(result.getData().getFields().get("temp_report").getSuccess());
        verify(collectionManager).writePoints(eq("dev-1"), eq(Map.of(point, 25)));
    }

    @Test
    void shouldRejectReadOnlyPointBeforeProtocolWrite() {
        DataPoint point = point("p1", "temperature", "R");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        ApiResult<BatchPointWriteResponse> result = controller.writePoints("dev-1", batchWrite("temperature", 25));

        assertEquals(1004, result.getCode());
        assertEquals("点位不可写", result.getData().getFields().get("temperature").getError());
        assertFalse(result.getData().getFields().get("temperature").getSuccess());
        verify(collectionManager, never()).writePoints(eq("dev-1"), anyMap());
    }

    @Test
    void shouldRejectReadOnlySinglePointBeforeProtocolWrite() {
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point("p1", "temperature", "R")));

        ApiResult<PointWriteResultResponse> result = controller.writePoint("dev-1", "temperature", singleWrite(25));

        assertEquals(1003, result.getCode());
        assertEquals("点位不可写: temperature", result.getMessage());
        verify(collectionManager, never()).writePoint(eq("dev-1"), any(), any());
    }

    @Test
    void shouldRejectMissingDeviceBeforeSingleWrite() {
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point("p1", "temperature", "RW")));
        when(configManager.getDevice("dev-1")).thenReturn(null);

        ApiResult<PointWriteResultResponse> result = controller.writePoint("dev-1", "temperature", singleWrite(25));

        assertBlocked(result, "设备未运行或未连接，禁止写入: dev-1");
        verify(collectionManager, never()).writePoint(eq("dev-1"), any(), any());
        verify(collectionService, never()).isDeviceRunning("dev-1");
    }

    @Test
    void shouldRejectStoppedDeviceBeforeBatchWrite() {
        when(collectionService.isDeviceRunning("dev-1")).thenReturn(false);

        ApiResult<BatchPointWriteResponse> result = controller.writePoints("dev-1", batchWrite("temperature", 25));

        assertBlocked(result, "设备未运行或未连接，禁止控制操作");
        verify(collectionManager, never()).writePoints(eq("dev-1"), anyMap());
        verify(collectionManager, never()).getCollector("dev-1");
    }

    @Test
    void shouldRejectMissingCollectorBeforeSingleWrite() {
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point("p1", "temperature", "RW")));
        when(collectionManager.getCollector("dev-1")).thenReturn(null);

        ApiResult<PointWriteResultResponse> result = controller.writePoint("dev-1", "temperature", singleWrite(25));

        assertBlocked(result, "设备未运行或未连接，禁止写入: dev-1");
        verify(collectionManager, never()).writePoint(eq("dev-1"), any(), any());
        verify(collectionManager, never()).isDeviceConnected("dev-1");
    }

    @Test
    void shouldRejectDisconnectedDeviceBeforeCommand() {
        when(collectionManager.isDeviceConnected("dev-1")).thenReturn(false);

        ApiResult<DeviceCommandResponse> result = controller.executeCommand("dev-1", command());

        assertBlocked(result, "设备未运行或未连接，禁止控制操作");
        verify(collectionManager, never()).executeCommand(eq("dev-1"), any(), anyMap());
    }

    @Test
    void shouldExecuteCommand() {
        DeviceCommandRequest request = command();
        when(collectionManager.executeCommand("dev-1", "browse", request.getParams()))
                .thenReturn(Map.of("accepted", true));

        ApiResult<DeviceCommandResponse> result = controller.executeCommand("dev-1", request);

        assertEquals(200, result.getCode());
        assertTrue(((Map<?, ?>) result.getData().getResult()).containsKey("accepted"));
        verify(collectionManager).executeCommand("dev-1", "browse", request.getParams());
    }

    private void assertBlocked(ApiResult<?> result, String message) {
        assertEquals(1004, result.getCode());
        assertEquals(message, result.getMessage());
        assertNull(result.getData());
    }

    private PointWriteRequest singleWrite(Object value) {
        PointWriteRequest request = new PointWriteRequest();
        request.setValue(value);
        return request;
    }

    private PointWriteRequest batchWrite(String field, Object value) {
        PointWriteRequest request = new PointWriteRequest();
        request.setValues(new LinkedHashMap<>(Map.of(field, value)));
        return request;
    }

    private DeviceCommandRequest command() {
        DeviceCommandRequest request = new DeviceCommandRequest();
        request.setCommand("browse");
        request.setParams(Map.of("depth", 1));
        return request;
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
