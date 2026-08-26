package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.BatchPointWriteFieldResponse;
import com.wangbin.collector.api.controller.dto.BatchPointWriteResponse;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlCommandApplicationServiceTest {

    private static final String DEVICE_ID = "dev-1";
    private static final String ERROR_PROTOCOL_WRITE_FALSE = "协议写入返回失败";
    private static final String ERROR_DUPLICATE_CONFLICT = "同一批次重复映射到同一点位且写入值不一致";

    private ConfigManager configManager;
    private CollectionManager collectionManager;
    private ControlCommandApplicationService applicationService;

    @BeforeEach
    void setUp() {
        configManager = mock(ConfigManager.class);
        collectionManager = mock(CollectionManager.class);
        applicationService = new ControlCommandApplicationService(
                configManager, collectionManager, new DevicePointResolver(configManager));
    }

    @Test
    void writePointsShouldApplyProtocolResultsToDifferentSubmittedFields() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        DataPoint pressure = point("p2", "pressure", "压力", "RW", "pressure_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature, pressure));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap()))
                .thenReturn(linkedBooleanMap("p1", true, "p2", false));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "pressure", 100)));

        assertEquals(200, result.getCode());
        assertEquals("批量点位写入完成", result.getMessage());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(2L, data.getMapped());
        assertEquals(1L, data.getSuccess());
        assertField(data, "temperature", true, true, null, "p1", "temperature", 25);
        assertField(data, "pressure", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p2", "pressure", 100);
    }

    @Test
    void writePointsShouldWriteSamePointSameValueOnceAndPropagateSuccessToAllSubmittedFields() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("p1", true));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "temp_report", 25)));

        assertEquals(200, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(2L, data.getMapped());
        assertEquals(2L, data.getSuccess());
        assertField(data, "temperature", true, true, null, "p1", "temperature", 25);
        assertField(data, "temp_report", true, true, null, "p1", "temperature", 25);
        assertNoPending(data);

        Map<DataPoint, Object> writePlan = captureWritePlan();
        assertEquals(1, writePlan.size());
        assertSame(temperature, writePlan.keySet().iterator().next());
        assertEquals(25, writePlan.get(temperature));
    }

    @Test
    void writePointsShouldRejectSamePointDifferentValuesWithoutProtocolWrite() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "temp_report", 30)));

        assertEquals(1004, result.getCode());
        assertEquals("批量点位写入失败", result.getMessage());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(2L, data.getMapped());
        assertEquals(0L, data.getSuccess());
        assertField(data, "temperature", true, false, ERROR_DUPLICATE_CONFLICT, "p1", "temperature", 25);
        assertField(data, "temp_report", true, false, ERROR_DUPLICATE_CONFLICT, "p1", "temperature", 30);
        assertNoPending(data);
        verify(collectionManager, never()).writePoints(eq(DEVICE_ID), anyMap());
    }

    @Test
    void writePointsShouldKeepValidPointWhenAnotherPointHasDuplicateConflict() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        DataPoint pressure = point("p2", "pressure", "压力", "RW", "pressure_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature, pressure));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("p2", true));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "temp_report", 30, "pressure", 100)));

        assertEquals(200, result.getCode());
        assertEquals("批量点位写入完成", result.getMessage());
        BatchPointWriteResponse data = result.getData();
        assertEquals(3, data.getTotal());
        assertEquals(3L, data.getMapped());
        assertEquals(1L, data.getSuccess());
        assertField(data, "temperature", true, false, ERROR_DUPLICATE_CONFLICT, "p1", "temperature", 25);
        assertField(data, "temp_report", true, false, ERROR_DUPLICATE_CONFLICT, "p1", "temperature", 30);
        assertField(data, "pressure", true, true, null, "p2", "pressure", 100);
        assertNoPending(data);

        Map<DataPoint, Object> writePlan = captureWritePlan();
        assertEquals(1, writePlan.size());
        assertTrue(writePlan.containsKey(pressure));
        assertFalse(writePlan.containsKey(temperature));
        assertEquals(100, writePlan.get(pressure));
    }

    @Test
    void writePointsShouldPropagateProtocolResultWhenPointIdIsNull() {
        DataPoint temperature = point(null, "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("temperature", true));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temp_report", 25)));

        assertEquals(200, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(1, data.getTotal());
        assertEquals(1L, data.getMapped());
        assertEquals(1L, data.getSuccess());
        assertField(data, "temp_report", true, true, null, null, "temperature", 25);
        assertNoPending(data);
    }

    @Test
    void writePointsShouldPropagateReportFieldProtocolResultWhenPointIdIsNull() {
        DataPoint temperature = point(null, "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("temp_report", true));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25)));

        assertEquals(200, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(1, data.getTotal());
        assertEquals(1L, data.getMapped());
        assertEquals(1L, data.getSuccess());
        assertField(data, "temperature", true, true, null, null, "temperature", 25);
        assertNoPending(data);
    }

    @Test
    void writePointsShouldPropagateProtocolFailureToAllSubmittedFieldsForSamePoint() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("p1", false));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "temp_report", 25)));

        assertEquals(1004, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(2L, data.getMapped());
        assertEquals(0L, data.getSuccess());
        assertField(data, "temperature", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p1", "temperature", 25);
        assertField(data, "temp_report", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p1", "temperature", 25);
        assertNoPending(data);
        assertEquals(1, captureWritePlan().size());
    }

    @Test
    void writePointsShouldMarkProtocolNullResultAsFailureWithoutPendingFields() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(null);

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25)));

        assertEquals(1004, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(1, data.getTotal());
        assertEquals(1L, data.getMapped());
        assertEquals(0L, data.getSuccess());
        assertField(data, "temperature", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p1", "temperature", 25);
        assertNoPending(data);
    }

    @Test
    void writePointsShouldMarkProtocolEmptyResultAsFailureWithoutPendingFields() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of());

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25)));

        assertEquals(1004, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(1, data.getTotal());
        assertEquals(1L, data.getMapped());
        assertEquals(0L, data.getSuccess());
        assertField(data, "temperature", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p1", "temperature", 25);
        assertNoPending(data);
    }

    @Test
    void writePointsShouldMarkMissingProtocolResultAsFailure() {
        DataPoint temperature = point("p1", "temperature", "温度", "RW", "temp_report");
        DataPoint pressure = point("p2", "pressure", "压力", "RW", "pressure_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(temperature, pressure));
        when(collectionManager.writePoints(eq(DEVICE_ID), anyMap())).thenReturn(Map.of("p1", true));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "pressure", 100)));

        assertEquals(200, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(2L, data.getMapped());
        assertEquals(1L, data.getSuccess());
        assertField(data, "temperature", true, true, null, "p1", "temperature", 25);
        assertField(data, "pressure", true, false, ERROR_PROTOCOL_WRITE_FALSE, "p2", "pressure", 100);
        assertNoPending(data);
    }

    @Test
    void writePointsShouldKeepReadOnlyAndMissingPointTerminalWithoutProtocolWrite() {
        DataPoint readOnly = point("p1", "temperature", "温度", "R", "temp_report");
        when(configManager.getDataPoints(DEVICE_ID)).thenReturn(List.of(readOnly));

        ApiResult<BatchPointWriteResponse> result = applicationService.writePoints(DEVICE_ID,
                request(linkedValues("temperature", 25, "missing", 30)));

        assertEquals(1004, result.getCode());
        BatchPointWriteResponse data = result.getData();
        assertEquals(2, data.getTotal());
        assertEquals(1L, data.getMapped());
        assertEquals(0L, data.getSuccess());
        assertField(data, "temperature", true, false, "点位不可写", "p1", "temperature", 25);
        assertField(data, "missing", false, false, "点位不存在", null, null, 30);
        assertNoPending(data);
        verify(collectionManager, never()).writePoints(eq(DEVICE_ID), anyMap());
    }

    private PointWriteRequest request(Map<String, Object> values) {
        PointWriteRequest request = new PointWriteRequest();
        request.setValues(values);
        return request;
    }

    private LinkedHashMap<String, Object> linkedValues(Object... keyValues) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private LinkedHashMap<String, Boolean> linkedBooleanMap(String firstKey,
                                                           Boolean firstValue,
                                                           String secondKey,
                                                           Boolean secondValue) {
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<DataPoint, Object> captureWritePlan() {
        ArgumentCaptor<Map<DataPoint, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(collectionManager).writePoints(eq(DEVICE_ID), captor.capture());
        return captor.getValue();
    }

    private DataPoint point(String pointId, String pointCode, String pointName, String readWrite, String reportField) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointName);
        point.setReadWrite(readWrite);
        point.setStatus(1);
        point.setAdditionalConfig(Map.of("reportField", reportField));
        return point;
    }

    private void assertField(BatchPointWriteResponse data,
                             String field,
                             boolean mapped,
                             boolean success,
                             String error,
                             String pointId,
                             String pointCode,
                             Object value) {
        BatchPointWriteFieldResponse fieldResponse = data.getFields().get(field);
        assertEquals(mapped, fieldResponse.getMapped());
        assertEquals(success, fieldResponse.getSuccess());
        assertEquals(error, fieldResponse.getError());
        assertEquals(pointId, fieldResponse.getPointId());
        assertEquals(pointCode, fieldResponse.getPointCode());
        assertEquals(value, fieldResponse.getValue());
    }

    private void assertNoPending(BatchPointWriteResponse data) {
        data.getFields().forEach((field, fieldResponse) -> assertFalse("等待写入".equals(fieldResponse.getError()), field));
    }
}
