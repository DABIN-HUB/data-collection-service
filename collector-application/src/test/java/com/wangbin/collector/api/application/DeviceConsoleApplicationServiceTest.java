package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.DeviceStatisticsResponse;
import com.wangbin.collector.api.controller.dto.DeviceStatusResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceConsoleApplicationServiceTest {

    @Mock
    private CollectionService collectionService;

    private DeviceConsoleApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DeviceConsoleApplicationService(collectionService);
    }

    @Test
    void startDeviceShouldReturnSuccessWhenCollectionStarts() {
        when(collectionService.startDevice("dev-1")).thenReturn(true);

        ApiResult<Object> result = service.startDevice("dev-1");

        assertEquals("success", result.getStatus());
        assertEquals("设备启动成功", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void startDeviceShouldReturnErrorWhenCollectionDoesNotStart() {
        when(collectionService.startDevice("dev-1")).thenReturn(false);

        ApiResult<Object> result = service.startDevice("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("设备已启动或启动失败", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void startDeviceShouldConvertExceptionToDeviceError() {
        when(collectionService.startDevice("dev-1")).thenThrow(new RuntimeException("boom"));

        ApiResult<Object> result = service.startDevice("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("启动异常: boom", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void startLocalDeviceShouldReturnSuccessForLocalDevice() {
        when(collectionService.startLocalDevice("local-1")).thenReturn(true);

        ApiResult<Object> result = service.startLocalDevice("local-1");

        assertEquals("success", result.getStatus());
        assertEquals("本地临时设备启动成功", result.getMessage());
        assertEquals("local-1", result.getDeviceId());
    }

    @Test
    void startLocalDeviceShouldReturnErrorForRejectedDevice() {
        when(collectionService.startLocalDevice("remote-1")).thenReturn(false);

        ApiResult<Object> result = service.startLocalDevice("remote-1");

        assertEquals("error", result.getStatus());
        assertEquals("设备不是本地临时设备，或启动失败", result.getMessage());
        assertEquals("remote-1", result.getDeviceId());
    }

    @Test
    void startLocalDeviceShouldConvertExceptionToDeviceError() {
        when(collectionService.startLocalDevice("local-1")).thenThrow(new RuntimeException("local down"));

        ApiResult<Object> result = service.startLocalDevice("local-1");

        assertEquals("error", result.getStatus());
        assertEquals("启动异常: local down", result.getMessage());
        assertEquals("local-1", result.getDeviceId());
    }

    @Test
    void stopDeviceShouldReturnSuccessWhenStopped() {
        when(collectionService.stopDevice("dev-1")).thenReturn(true);

        ApiResult<Object> result = service.stopDevice("dev-1");

        assertEquals("success", result.getStatus());
        assertEquals("设备已停止", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void stopDeviceShouldReturnErrorWhenStopFails() {
        when(collectionService.stopDevice("dev-1")).thenReturn(false);

        ApiResult<Object> result = service.stopDevice("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("设备停止失败或已经停止", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void stopDeviceShouldConvertExceptionToDeviceError() {
        when(collectionService.stopDevice("dev-1")).thenThrow(new RuntimeException("stop down"));

        ApiResult<Object> result = service.stopDevice("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("停止异常: stop down", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    @Test
    void reloadAllDevicesShouldReturnTriggeredMessage() {
        ApiResult<Object> result = service.reloadAllDevices();

        verify(collectionService).reloadAllDevices();
        assertEquals("success", result.getStatus());
        assertEquals("已触发设备重新加载", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void reloadAllDevicesShouldConvertExceptionToStatusError() {
        doThrow(new RuntimeException("scheduler rejected")).when(collectionService).reloadAllDevices();

        ApiResult<Object> result = service.reloadAllDevices();

        assertEquals("error", result.getStatus());
        assertEquals("重新加载异常: scheduler rejected", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void getDeviceStatusShouldConvertRuntimeMapToDto() {
        when(collectionService.getDeviceStatus("dev-1")).thenReturn(deviceStatus());

        ApiResult<DeviceStatusResponse> result = service.getDeviceStatus("dev-1");

        assertEquals("success", result.getStatus());
        assertEquals("dev-1", result.getDeviceId());
        assertEquals("dev-1", result.getData().getDeviceId());
        assertEquals(Boolean.TRUE, result.getData().getIsRunning());
        assertEquals(Boolean.FALSE, result.getData().getIsStarting());
        assertEquals(Boolean.TRUE, result.getData().getConnected());
        assertEquals(Boolean.FALSE, result.getData().getReconnecting());
        assertEquals(12345L, result.getData().getReconnectNextRetryAt());
        assertEquals(10, result.getData().getStatistics().getTotalExecutions());
        assertEquals(80.0D, result.getData().getStatistics().getSuccessRate());
        assertEquals("LOW", result.getData().getPerformance().getFailureRisk());
    }

    @Test
    void getDeviceStatusShouldConvertExceptionToStatusErrorWithDeviceId() {
        when(collectionService.getDeviceStatus("dev-1")).thenThrow(new RuntimeException("status down"));

        ApiResult<DeviceStatusResponse> result = service.getDeviceStatus("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("获取状态失败: status down", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
        assertNull(result.getData());
    }

    @Test
    void getAllStatisticsShouldPreserveOrderAndConvertDtos() {
        Map<String, Map<String, Object>> statistics = new LinkedHashMap<>();
        statistics.put("dev-1", statistics("dev-1", true, 10, 8, 2, 80.0D));
        statistics.put("dev-2", statistics("dev-2", false, 4, 4, 0, 100.0D));
        when(collectionService.getAllStatistics()).thenReturn(statistics);

        ApiResult<Map<String, DeviceStatisticsResponse>> result = service.getAllStatistics();

        assertEquals("success", result.getStatus());
        assertEquals(List.of("dev-1", "dev-2"), new ArrayList<>(result.getData().keySet()));
        assertEquals(10, result.getData().get("dev-1").getTotalExecutions());
        assertEquals(100.0D, result.getData().get("dev-2").getSuccessRate());
    }

    @Test
    void getAllStatisticsShouldReturnEmptyMapWhenNoStatistics() {
        when(collectionService.getAllStatistics()).thenReturn(Map.of());

        ApiResult<Map<String, DeviceStatisticsResponse>> result = service.getAllStatistics();

        assertEquals("success", result.getStatus());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void getAllStatisticsShouldConvertExceptionToStatusError() {
        when(collectionService.getAllStatistics()).thenThrow(new RuntimeException("stats down"));

        ApiResult<Map<String, DeviceStatisticsResponse>> result = service.getAllStatistics();

        assertEquals("error", result.getStatus());
        assertEquals("获取统计异常: stats down", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void getRunningDevicesShouldReturnDevicesWithCount() {
        List<String> devices = List.of("dev-1", "dev-2");
        when(collectionService.getRunningDevices()).thenReturn(devices);

        ApiResult<List<String>> result = service.getRunningDevices();

        assertEquals("success", result.getStatus());
        assertSame(devices, result.getData());
        assertEquals(2, result.getCount());
    }

    @Test
    void getRunningDevicesShouldReturnEmptyListWithZeroCount() {
        List<String> devices = List.of();
        when(collectionService.getRunningDevices()).thenReturn(devices);

        ApiResult<List<String>> result = service.getRunningDevices();

        assertEquals("success", result.getStatus());
        assertSame(devices, result.getData());
        assertEquals(0, result.getCount());
    }

    @Test
    void getRunningDevicesShouldConvertExceptionToStatusError() {
        when(collectionService.getRunningDevices()).thenThrow(new RuntimeException("running down"));

        ApiResult<List<String>> result = service.getRunningDevices();

        assertEquals("error", result.getStatus());
        assertEquals("获取设备列表异常: running down", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void getDeviceRuntimeSnapshotsShouldReturnSnapshotsWithCount() {
        List<DeviceRuntimeSnapshot> snapshots = List.of(
                runtimeSnapshot("dev-1", DeviceRuntimePhase.ONLINE),
                runtimeSnapshot("dev-2", DeviceRuntimePhase.STOPPED));
        when(collectionService.getDeviceRuntimeSnapshots()).thenReturn(snapshots);

        ApiResult<List<DeviceRuntimeSnapshot>> result = service.getDeviceRuntimeSnapshots();

        assertEquals("success", result.getStatus());
        assertSame(snapshots, result.getData());
        assertEquals(2, result.getCount());
        assertEquals("dev-1", result.getData().get(0).deviceId());
        assertEquals("dev-2", result.getData().get(1).deviceId());
    }

    @Test
    void getDeviceRuntimeSnapshotsShouldReturnEmptyListWithZeroCount() {
        List<DeviceRuntimeSnapshot> snapshots = List.of();
        when(collectionService.getDeviceRuntimeSnapshots()).thenReturn(snapshots);

        ApiResult<List<DeviceRuntimeSnapshot>> result = service.getDeviceRuntimeSnapshots();

        assertEquals("success", result.getStatus());
        assertSame(snapshots, result.getData());
        assertEquals(0, result.getCount());
    }

    @Test
    void getDeviceRuntimeSnapshotsShouldConvertExceptionToStatusError() {
        when(collectionService.getDeviceRuntimeSnapshots()).thenThrow(new RuntimeException("runtime down"));

        ApiResult<List<DeviceRuntimeSnapshot>> result = service.getDeviceRuntimeSnapshots();

        assertEquals("error", result.getStatus());
        assertEquals("获取设备运行快照异常: runtime down", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void isDeviceRunningShouldReturnTopLevelRunningTrue() {
        when(collectionService.isDeviceRunning("dev-1")).thenReturn(true);

        ApiResult<Object> result = service.isDeviceRunning("dev-1");

        assertEquals("success", result.getStatus());
        assertEquals("dev-1", result.getDeviceId());
        assertTrue(result.getRunning());
        assertNull(result.getData());
    }

    @Test
    void isDeviceRunningShouldReturnTopLevelRunningFalse() {
        when(collectionService.isDeviceRunning("dev-1")).thenReturn(false);

        ApiResult<Object> result = service.isDeviceRunning("dev-1");

        assertEquals("success", result.getStatus());
        assertEquals("dev-1", result.getDeviceId());
        assertFalse(result.getRunning());
        assertNull(result.getData());
    }

    @Test
    void isDeviceRunningShouldConvertExceptionToDeviceError() {
        when(collectionService.isDeviceRunning("dev-1")).thenThrow(new RuntimeException("running flag down"));

        ApiResult<Object> result = service.isDeviceRunning("dev-1");

        assertEquals("error", result.getStatus());
        assertEquals("查询运行状态异常: running flag down", result.getMessage());
        assertEquals("dev-1", result.getDeviceId());
    }

    private Map<String, Object> deviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceId", "dev-1");
        status.put("isRunning", true);
        status.put("isStarting", false);
        status.put("connected", true);
        status.put("reconnecting", false);
        status.put("reconnectNextRetryAt", 12345L);
        status.put("statistics", statistics("dev-1", true, 10, 8, 2, 80.0D));
        status.put("performance", performance());
        return status;
    }

    private Map<String, Object> statistics(String deviceId, boolean running, int totalExecutions,
                                           int successfulExecutions, int failedExecutions,
                                           double successRate) {
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("deviceId", deviceId);
        statistics.put("isRunning", running);
        statistics.put("runningDuration", running ? 1000L : 0L);
        statistics.put("totalExecutions", totalExecutions);
        statistics.put("successfulExecutions", successfulExecutions);
        statistics.put("failedExecutions", failedExecutions);
        statistics.put("totalPoints", 30);
        statistics.put("currentTaskPoints", 5);
        statistics.put("averageExecutionTime", 25L);
        statistics.put("successRate", successRate);
        statistics.put("lastExecutionTime", 2000L);
        return statistics;
    }

    private Map<String, Object> performance() {
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("deviceId", "dev-1");
        performance.put("totalPoints", 30);
        performance.put("successfulBatches", 8);
        performance.put("failedBatches", 2);
        performance.put("averageBatchTime", 25L);
        performance.put("currentBatchSize", 5);
        performance.put("maxBatchSize", 20);
        performance.put("successRate", 80.0D);
        performance.put("healthScore", 90.0D);
        performance.put("failureRisk", "LOW");
        performance.put("consecutiveFailures", 1);
        performance.put("averageResponseTime", 25L);
        performance.put("recentResponseTimes", List.of(20L, 30L));
        return performance;
    }

    private DeviceRuntimeSnapshot runtimeSnapshot(String deviceId, DeviceRuntimePhase phase) {
        return new DeviceRuntimeSnapshot(
                deviceId,
                phase,
                phase == DeviceRuntimePhase.ONLINE,
                false,
                phase == DeviceRuntimePhase.ONLINE,
                false,
                0L,
                100L,
                1L,
                90L,
                0,
                0L,
                null,
                200L);
    }
}
