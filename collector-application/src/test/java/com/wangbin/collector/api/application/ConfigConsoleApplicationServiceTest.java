package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.controller.dto.DevicePointConfigResponse;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigRequest;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigResponse;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigConsoleApplicationServiceTest {

    private ConfigManager configManager;
    private ConfigSyncService configSyncService;
    private CollectionService collectionService;
    private PointRuntimeStateService pointRuntimeStateService;
    private ConfigConsoleApplicationService service;

    @BeforeEach
    void setUp() {
        configManager = mock(ConfigManager.class);
        configSyncService = mock(ConfigSyncService.class);
        collectionService = mock(CollectionService.class);
        pointRuntimeStateService = mock(PointRuntimeStateService.class);
        LocalDeviceConfigApplicationService localDeviceConfigApplicationService =
                new LocalDeviceConfigApplicationService(configManager, collectionService);
        ConfigImportExportApplicationService configImportExportApplicationService =
                new ConfigImportExportApplicationService(configManager, collectionService, new SensitiveConfigSanitizer());
        service = new ConfigConsoleApplicationService(
                configManager,
                configSyncService,
                new SensitiveConfigSanitizer(),
                pointRuntimeStateService,
                localDeviceConfigApplicationService,
                configImportExportApplicationService,
                new ConfigDiffCalculator());
    }

    @Test
    void createLocalDeviceShouldSaveAndStartWhenRequested() {
        LocalDeviceConfigRequest request = localRequest("local-create");
        request.setStartAfterSave(true);
        when(configManager.saveLocalDeviceConfig(
                request.getDevice(),
                request.getConnection(),
                request.getPoints(),
                false)).thenReturn(true);
        when(collectionService.startLocalDevice("local-create")).thenReturn(true);

        ApiResult<LocalDeviceConfigResponse> result = service.createLocalDevice(request);

        assertEquals("success", result.getStatus());
        assertEquals("本地临时设备已保存", result.getMessage());
        assertEquals("local-create", result.getData().getDeviceId());
        assertEquals(ConfigManager.CONFIG_SOURCE_LOCAL, result.getData().getConfigSource());
        assertEquals(Boolean.TRUE, result.getData().getTemporaryConfig());
        assertEquals(Boolean.TRUE, result.getData().getStarted());
        assertEquals(1, result.getData().getPointCount());
        verify(collectionService).startLocalDevice("local-create");
    }

    @Test
    void createLocalDeviceShouldRejectDuplicateWithoutOverwrite() {
        LocalDeviceConfigRequest request = localRequest("local-dup");
        when(configManager.saveLocalDeviceConfig(any(DeviceInfo.class), any(DeviceConnection.class), anyList(), eq(false)))
                .thenThrow(new IllegalArgumentException("local temporary device already exists: local-dup"));

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.createLocalDevice(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("local temporary device already exists: local-dup", exception.getMessage());
        verify(collectionService, never()).startLocalDevice("local-dup");
    }

    @Test
    void createLocalDeviceShouldPassOverwriteFlag() {
        LocalDeviceConfigRequest request = localRequest("local-overwrite");
        request.setOverwrite(true);
        when(configManager.saveLocalDeviceConfig(
                request.getDevice(),
                request.getConnection(),
                request.getPoints(),
                true)).thenReturn(true);

        ApiResult<LocalDeviceConfigResponse> result = service.createLocalDevice(request);

        assertEquals("success", result.getStatus());
        verify(configManager).saveLocalDeviceConfig(
                request.getDevice(),
                request.getConnection(),
                request.getPoints(),
                true);
    }

    @Test
    void updateLocalDeviceShouldUsePathDeviceIdAndOverwrite() {
        LocalDeviceConfigRequest request = localRequest("body-id");
        when(configManager.saveLocalDeviceConfig(
                request.getDevice(),
                request.getConnection(),
                request.getPoints(),
                true)).thenReturn(true);

        ApiResult<LocalDeviceConfigResponse> result = service.updateLocalDevice("path-id", request);

        assertEquals("path-id", request.getDevice().getDeviceId());
        assertEquals("path-id", result.getData().getDeviceId());
        verify(configManager).saveLocalDeviceConfig(
                request.getDevice(),
                request.getConnection(),
                request.getPoints(),
                true);
    }

    @Test
    void getLocalDeviceShouldRejectNonLocalDevice() {
        when(configManager.isLocalTemporaryDevice("remote-1")).thenReturn(false);

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.getLocalDevice("remote-1"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("只能读取本地临时设备配置: remote-1", exception.getMessage());
    }

    @Test
    void deleteRunningLocalDeviceShouldStopBeforeDelete() {
        when(configManager.isLocalTemporaryDevice("local-delete")).thenReturn(true);
        when(collectionService.isDeviceRunning("local-delete")).thenReturn(true);
        when(collectionService.stopDevice("local-delete")).thenReturn(true);
        when(configManager.deleteLocalDeviceConfig("local-delete")).thenReturn(true);

        ApiResult<?> result = service.deleteLocalDevice("local-delete");

        assertEquals("success", result.getStatus());
        assertEquals("本地临时设备已删除", result.getMessage());
        verify(collectionService).stopDevice("local-delete");
        verify(configManager).deleteLocalDeviceConfig("local-delete");
    }

    @Test
    void deleteRunningLocalDeviceShouldNotDeleteWhenStopFails() {
        when(configManager.isLocalTemporaryDevice("local-running")).thenReturn(true);
        when(collectionService.isDeviceRunning("local-running")).thenReturn(true);
        when(collectionService.stopDevice("local-running")).thenReturn(false);

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.deleteLocalDevice("local-running"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("设备正在运行且停止失败，未删除: local-running", exception.getMessage());
        verify(configManager, never()).deleteLocalDeviceConfig("local-running");
    }

    @Test
    void deleteLocalDeviceShouldRejectNonLocalDevice() {
        when(configManager.isLocalTemporaryDevice("remote-delete")).thenReturn(false);

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.deleteLocalDevice("remote-delete"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("只能删除本地临时设备配置: remote-delete", exception.getMessage());
        verify(collectionService, never()).stopDevice("remote-delete");
        verify(configManager, never()).deleteLocalDeviceConfig("remote-delete");
    }

    @Test
    void diffShouldReportCompletelyInSync() {
        DeviceInfo local = device("sync", "设备");
        DeviceConnection connection = connection("sync", "127.0.0.1");
        DataPoint point = point("sync", "temperature", "40001");
        stubDiffInputs("sync", local, device("sync", "设备"), connection,
                connection("sync", "127.0.0.1"), List.of(point), List.of(point("sync", "temperature", "40001")));

        ConfigDiffResponse diff = service.diff("sync").getData();

        assertFalse(diff.isDeviceChanged());
        assertFalse(diff.isConnectionChanged());
        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void diffShouldReportDeviceMetadataChanged() {
        stubDiffInputs("dev-change", device("dev-change", "本地"), device("dev-change", "远端"),
                null, null, Collections.emptyList(), Collections.emptyList());

        ConfigDiffResponse diff = service.diff("dev-change").getData();

        assertTrue(diff.isDeviceChanged());
        assertFalse(diff.isConnectionChanged());
    }

    @Test
    void diffShouldReportConnectionChanged() {
        DeviceInfo device = device("conn-change", "设备");
        stubDiffInputs("conn-change", device, device("conn-change", "设备"),
                connection("conn-change", "127.0.0.1"), connection("conn-change", "192.168.1.10"),
                Collections.emptyList(), Collections.emptyList());

        ConfigDiffResponse diff = service.diff("conn-change").getData();

        assertFalse(diff.isDeviceChanged());
        assertTrue(diff.isConnectionChanged());
    }

    @Test
    void diffShouldReportPointAddedOnRemoteAsMissingLocalPoint() {
        DeviceInfo device = device("point-added", "设备");
        stubDiffInputs("point-added", device, device("point-added", "设备"), null, null,
                Collections.emptyList(), List.of(point("point-added", "new-point", "40002")));

        ConfigDiffResponse diff = service.diff("point-added").getData();

        assertEquals(List.of("new-point"), diff.getMissingPointCodes());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void diffShouldReportPointRemovedOnRemoteAsExtraLocalPoint() {
        DeviceInfo device = device("point-removed", "设备");
        stubDiffInputs("point-removed", device, device("point-removed", "设备"), null, null,
                List.of(point("point-removed", "old-point", "40001")), Collections.emptyList());

        ConfigDiffResponse diff = service.diff("point-removed").getData();

        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertEquals(List.of("old-point"), diff.getExtraPointCodes());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void diffShouldReportChangedPointByPointCode() {
        DeviceInfo device = device("point-changed", "设备");
        stubDiffInputs("point-changed", device, device("point-changed", "设备"), null, null,
                List.of(point("point-changed", "temperature", "40001")),
                List.of(point("point-changed", "temperature", "40002")));

        ConfigDiffResponse diff = service.diff("point-changed").getData();

        assertEquals(List.of("temperature"), diff.getChangedPointCodes());
        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
    }

    @Test
    void diffShouldHandleNullAndEmptyPoints() {
        DeviceInfo device = device("empty-points", "设备");
        when(configManager.getDevice("empty-points")).thenReturn(device);
        when(configSyncService.getDeviceConfigs()).thenReturn(Map.of("empty-points", device("empty-points", "设备")));
        when(configManager.getConnectionConfig("empty-points")).thenReturn(null);
        when(configSyncService.getConnectionConfigs()).thenReturn(Collections.emptyMap());
        when(configManager.getDataPoints("empty-points")).thenReturn(null);
        when(configSyncService.getPointConfigs()).thenReturn(Collections.emptyMap());

        ConfigDiffResponse diff = service.diff("empty-points").getData();

        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void exportConfigsShouldSanitizeConnection() {
        DeviceInfo device = device("export-1", "导出设备");
        DeviceConnection connection = connection("export-1", "127.0.0.1");
        connection.setPassword("placeholder-value");
        when(configManager.getAllDeviceContexts())
                .thenReturn(List.of(DeviceContext.of(device, connection, List.of(point("export-1", "temperature", "40001")))));

        ConfigExportResponse response = service.exportConfigs().getData();

        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE,
                response.getBundles().get(0).getConnection().getPassword());
        assertEquals("placeholder-value", connection.getPassword());
    }

    @Test
    void importConfigsShouldImportValidBundlesAndReloadWhenRequested() {
        ConfigImportRequest request = importRequest(List.of(bundle("import-1")));
        request.setReloadAfterImport(true);
        when(configManager.replaceDeviceContextsAtomically(anyList())).thenReturn(true);

        ApiResult<ConfigImportResult> result = service.importConfigs(request);

        assertEquals("success", result.getStatus());
        assertEquals("导入完成", result.getMessage());
        assertEquals(1, result.getData().getTotal());
        assertEquals(1, result.getData().getSuccess());
        assertTrue(result.getData().getFailedDevices().isEmpty());
        verify(collectionService).reloadAllDevices();
    }

    @Test
    void importConfigsShouldRejectEmptyRequest() {
        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.importConfigs(new ConfigImportRequest()));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("导入内容不能为空", exception.getMessage());
    }

    @Test
    void importConfigsShouldRestoreMaskedConnectionValues() {
        DeviceConnection existing = connection("import-mask", "127.0.0.1");
        existing.setPassword("placeholder-value");
        DeviceConnection incoming = connection("import-mask", "127.0.0.1");
        incoming.setPassword(SensitiveConfigSanitizer.MASKED_VALUE);
        ConfigBundle bundle = ConfigBundle.builder()
                .device(device("import-mask", "导入设备"))
                .connection(incoming)
                .points(List.of(point("import-mask", "temperature", "40001")))
                .build();
        when(configManager.getConnectionConfig("import-mask")).thenReturn(existing);
        when(configManager.replaceDeviceContextsAtomically(anyList())).thenReturn(true);
        ArgumentCaptor<List<DeviceContext>> captor = ArgumentCaptor.forClass(List.class);

        service.importConfigs(importRequest(List.of(bundle)));

        verify(configManager).replaceDeviceContextsAtomically(captor.capture());
        assertEquals("placeholder-value", captor.getValue().get(0).getConnectionConfig().getPassword());
    }

    @Test
    void importConfigsShouldRejectInvalidBundle() {
        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.importConfigs(importRequest(List.of(ConfigBundle.builder().build()))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("导入内容存在缺少设备 ID 的配置", exception.getMessage());
        verify(configManager, never()).replaceDeviceContextsAtomically(anyList());
    }

    @Test
    void importConfigsShouldReportAtomicFailureCounts() {
        ConfigImportRequest request = importRequest(List.of(bundle("fail-1"), bundle("fail-2")));
        when(configManager.replaceDeviceContextsAtomically(anyList())).thenReturn(false);

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.importConfigs(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("导入失败，现有配置未发生变化", exception.getMessage());
        ConfigImportResult result = (ConfigImportResult) exception.getData();
        assertEquals(2, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(List.of("fail-1", "fail-2"), result.getFailedDevices());
        verify(collectionService, never()).reloadAllDevices();
    }

    @Test
    void importConfigsShouldReportDuplicateDeviceIdsAsAtomicFailureWithoutPartialSuccess() {
        ConfigImportRequest request = importRequest(List.of(bundle("dup-import"), bundle("dup-import")));
        when(configManager.replaceDeviceContextsAtomically(anyList())).thenReturn(false);

        ConfigApiException exception = assertThrows(ConfigApiException.class,
                () -> service.importConfigs(request));

        ConfigImportResult result = (ConfigImportResult) exception.getData();
        assertEquals(2, result.getTotal());
        assertEquals(0, result.getSuccess());
        assertEquals(List.of("dup-import", "dup-import"), result.getFailedDevices());
    }

    @Test
    void getDevicePointsWithAdaptiveShouldNotMutateOriginalPoint() {
        DataPoint original = point("adaptive-1", "temperature", "40001");
        when(configManager.containsDevice("adaptive-1")).thenReturn(true);
        when(configManager.getDataPoints("adaptive-1")).thenReturn(List.of(original));
        when(pointRuntimeStateService.snapshot("adaptive-1", original))
                .thenReturn(new PointRuntimeStateSnapshot(5000L, 3, "42", 0.25D, 12345L));

        DevicePointConfigResponse response = service.getDevicePoints("adaptive-1", true).getData();
        DataPoint projected = response.getPoints().get(0);

        assertEquals(5000L, projected.getCurrentCollectionInterval());
        assertEquals(3, projected.getStableCount());
        assertEquals("42", projected.getLastValue());
        assertEquals(0.25D, projected.getChangeRate());
        assertEquals(12345L, projected.getLastAdjustTime());
        assertEquals(0L, original.getCurrentCollectionInterval());
        assertEquals(0, original.getStableCount());
        assertNull(original.getLastValue());
        assertEquals(0D, original.getChangeRate());
        assertEquals(0L, original.getLastAdjustTime());
        assertFalse(projected == original);
    }

    private void stubDiffInputs(String deviceId,
                                DeviceInfo localDevice,
                                DeviceInfo remoteDevice,
                                DeviceConnection localConnection,
                                DeviceConnection remoteConnection,
                                List<DataPoint> localPoints,
                                List<DataPoint> remotePoints) {
        when(configManager.getDevice(deviceId)).thenReturn(localDevice);
        when(configSyncService.getDeviceConfigs()).thenReturn(remoteDevice == null
                ? Collections.emptyMap() : Map.of(deviceId, remoteDevice));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(localConnection);
        when(configSyncService.getConnectionConfigs()).thenReturn(remoteConnection == null
                ? Collections.emptyMap() : Map.of(deviceId, remoteConnection));
        when(configManager.getDataPoints(deviceId)).thenReturn(localPoints);
        when(configSyncService.getPointConfigs()).thenReturn(remotePoints == null
                ? Collections.emptyMap() : Map.of(deviceId, remotePoints));
    }

    private ConfigImportRequest importRequest(List<ConfigBundle> bundles) {
        ConfigImportRequest request = new ConfigImportRequest();
        request.setBundles(bundles);
        return request;
    }

    private ConfigBundle bundle(String deviceId) {
        return ConfigBundle.builder()
                .device(device(deviceId, "导入设备"))
                .connection(connection(deviceId, "127.0.0.1"))
                .points(List.of(point(deviceId, "temperature", "40001")))
                .build();
    }

    private LocalDeviceConfigRequest localRequest(String deviceId) {
        return LocalDeviceConfigRequest.builder()
                .device(device(deviceId, "本地设备"))
                .connection(connection(deviceId, "127.0.0.1"))
                .points(List.of(point(deviceId, "temperature", "40001")))
                .build();
    }

    private DeviceInfo device(String deviceId, String deviceName) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceName);
        device.setProtocolType("MODBUS_TCP");
        device.setCollectionInterval(2000);
        return device;
    }

    private DeviceConnection connection(String deviceId, String host) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost(host);
        connection.setPort(502);
        return connection;
    }

    private DataPoint point(String deviceId, String pointCode, String address) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setAddress(address);
        point.setDataType("FLOAT");
        return point;
    }
}
