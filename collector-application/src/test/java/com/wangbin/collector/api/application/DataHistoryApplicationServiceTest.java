package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import com.wangbin.collector.storage.service.HistoryDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataHistoryApplicationServiceTest {

    @Test
    void getPointHistoryShouldReturnDisabledWhenHistoryBeanMissing() {
        DataHistoryApplicationService service = newService(null, null);

        HistoryDataResponse response = service.getPointHistory("dev-1", "p-1", 100L, 200L, 10);

        assertEquals("disabled", response.getStatus());
        assertEquals("TDengine 历史存储未启用", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertNull(response.getCount());
        assertNull(response.getData());
    }

    @Test
    void getPointHistoryShouldReturnDisabledWhenHistoryServiceDisabled() {
        HistoryDataService historyDataService = mock(HistoryDataService.class);
        when(historyDataService.isEnabled()).thenReturn(false);
        DataHistoryApplicationService service = newService(historyDataService, null);

        HistoryDataResponse response = service.getPointHistory("dev-1", "p-1", 100L, 200L, 10);

        assertEquals("disabled", response.getStatus());
        assertEquals("TDengine 历史存储未启用", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        verify(historyDataService, never()).queryPointHistory("dev-1", "p-1", 100L, 200L, 10);
    }

    @Test
    void getPointHistoryShouldReturnRowsAndPreserveQueryParameters() {
        HistoryDataService historyDataService = mock(HistoryDataService.class);
        List<Map<String, Object>> rows = List.of(Map.of("value", 12.3D), Map.of("value", 45.6D));
        when(historyDataService.isEnabled()).thenReturn(true);
        when(historyDataService.queryPointHistory("dev-1", "p-1", 100L, 200L, 10)).thenReturn(rows);
        DataHistoryApplicationService service = newService(historyDataService, null);

        HistoryDataResponse response = service.getPointHistory("dev-1", "p-1", 100L, 200L, 10);

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertEquals(2, response.getCount());
        assertSame(rows, response.getData());
        assertEquals(100L, response.getStartTs());
        assertEquals(200L, response.getEndTs());
        assertNotNull(response.getTimestamp());
        verify(historyDataService).queryPointHistory("dev-1", "p-1", 100L, 200L, 10);
    }

    @Test
    void getPointHistoryShouldReturnErrorWhenQueryThrows() {
        HistoryDataService historyDataService = mock(HistoryDataService.class);
        when(historyDataService.isEnabled()).thenReturn(true);
        when(historyDataService.queryPointHistory("dev-1", "p-1", 100L, 200L, 10))
                .thenThrow(new IllegalStateException("td down"));
        DataHistoryApplicationService service = newService(historyDataService, null);

        HistoryDataResponse response = service.getPointHistory("dev-1", "p-1", 100L, 200L, 10);

        assertEquals("error", response.getStatus());
        assertEquals("查询失败: td down", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertNull(response.getData());
    }

    @Test
    void getRecentAlarmHistoryShouldReturnDisabledWhenAlarmBeanMissing() {
        DataHistoryApplicationService service = newService(null, null);

        AlarmHistoryDataResponse response = service.getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertRecentAlarmDisabledShape(response);
    }

    @Test
    void getRecentAlarmHistoryShouldReturnDisabledWhenAlarmServiceDisabled() {
        AlarmHistoryService alarmHistoryService = mock(AlarmHistoryService.class);
        when(alarmHistoryService.isEnabled()).thenReturn(false);
        DataHistoryApplicationService service = newService(null, alarmHistoryService);

        AlarmHistoryDataResponse response = service.getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertRecentAlarmDisabledShape(response);
        verify(alarmHistoryService, never()).queryRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);
    }

    @Test
    void getRecentAlarmHistoryShouldKeepRowsCountAndTotalSeparate() {
        AlarmHistoryService alarmHistoryService = mock(AlarmHistoryService.class);
        List<Map<String, Object>> rows = List.of(Map.of("alarm", "a"), Map.of("alarm", "b"));
        when(alarmHistoryService.isEnabled()).thenReturn(true);
        when(alarmHistoryService.queryRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10)).thenReturn(rows);
        when(alarmHistoryService.countRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L)).thenReturn(200L);
        DataHistoryApplicationService service = newService(null, alarmHistoryService);

        AlarmHistoryDataResponse response = service.getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertEquals("temperature", response.getPointCode());
        assertEquals("HIGH", response.getLevel());
        assertEquals("rule-1", response.getRuleId());
        assertEquals(2, response.getCount());
        assertEquals(200L, response.getTotal());
        assertSame(rows, response.getData());
        assertEquals(100L, response.getStartTs());
        assertEquals(200L, response.getEndTs());
        assertNotNull(response.getTimestamp());
        verify(alarmHistoryService).countRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L);
    }

    @Test
    void getRecentAlarmHistoryShouldReturnErrorWhenQueryThrows() {
        AlarmHistoryService alarmHistoryService = mock(AlarmHistoryService.class);
        when(alarmHistoryService.isEnabled()).thenReturn(true);
        when(alarmHistoryService.queryRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10))
                .thenThrow(new IllegalStateException("alarm down"));
        DataHistoryApplicationService service = newService(null, alarmHistoryService);

        AlarmHistoryDataResponse response = service.getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertEquals("error", response.getStatus());
        assertEquals("查询失败: alarm down", response.getMessage());
        assertEquals(0, response.getCount());
        assertEquals(Collections.emptyList(), response.getData());
        assertNull(response.getDeviceId());
    }

    @Test
    void getAlarmHistoryShouldReturnExistingDeviceDisabledShape() {
        DataHistoryApplicationService service = newService(null, null);

        AlarmHistoryDataResponse response = service.getAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertEquals("disabled", response.getStatus());
        assertEquals("TDengine 告警历史存储未启用", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertNull(response.getPointId());
        assertNull(response.getCount());
        assertNull(response.getData());
    }

    @Test
    void getAlarmHistoryShouldQueryDeviceAlarmWithoutTotalCount() {
        AlarmHistoryService alarmHistoryService = mock(AlarmHistoryService.class);
        List<Map<String, Object>> rows = List.of(Map.of("alarm", "a"), Map.of("alarm", "b"), Map.of("alarm", "c"));
        when(alarmHistoryService.isEnabled()).thenReturn(true);
        when(alarmHistoryService.queryAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10)).thenReturn(rows);
        DataHistoryApplicationService service = newService(null, alarmHistoryService);

        AlarmHistoryDataResponse response = service.getAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertEquals("success", response.getStatus());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertEquals("temperature", response.getPointCode());
        assertEquals("HIGH", response.getLevel());
        assertEquals("rule-1", response.getRuleId());
        assertEquals(3, response.getCount());
        assertNull(response.getTotal());
        assertSame(rows, response.getData());
        assertEquals(100L, response.getStartTs());
        assertEquals(200L, response.getEndTs());
        assertNotNull(response.getTimestamp());
        verify(alarmHistoryService, never()).countRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L);
    }

    @Test
    void getAlarmHistoryShouldReturnExistingErrorShapeWhenQueryThrows() {
        AlarmHistoryService alarmHistoryService = mock(AlarmHistoryService.class);
        when(alarmHistoryService.isEnabled()).thenReturn(true);
        when(alarmHistoryService.queryAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10))
                .thenThrow(new IllegalStateException("device alarm down"));
        DataHistoryApplicationService service = newService(null, alarmHistoryService);

        AlarmHistoryDataResponse response = service.getAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);

        assertEquals("error", response.getStatus());
        assertEquals("查询失败: device alarm down", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertNull(response.getPointId());
        assertNull(response.getData());
    }

    private void assertRecentAlarmDisabledShape(AlarmHistoryDataResponse response) {
        assertEquals("disabled", response.getStatus());
        assertEquals("TDengine 告警历史存储未启用", response.getMessage());
        assertEquals("dev-1", response.getDeviceId());
        assertEquals("p-1", response.getPointId());
        assertEquals("temperature", response.getPointCode());
        assertEquals("HIGH", response.getLevel());
        assertEquals("rule-1", response.getRuleId());
        assertEquals(0, response.getCount());
        assertEquals(Collections.emptyList(), response.getData());
        assertEquals(100L, response.getStartTs());
        assertEquals(200L, response.getEndTs());
    }

    @SuppressWarnings("unchecked")
    private DataHistoryApplicationService newService(HistoryDataService historyDataService,
                                                     AlarmHistoryService alarmHistoryService) {
        ObjectProvider<HistoryDataService> historyProvider = mock(ObjectProvider.class);
        ObjectProvider<AlarmHistoryService> alarmProvider = mock(ObjectProvider.class);
        when(historyProvider.getIfAvailable()).thenReturn(historyDataService);
        when(alarmProvider.getIfAvailable()).thenReturn(alarmHistoryService);
        return new DataHistoryApplicationService(historyProvider, alarmProvider);
    }
}
