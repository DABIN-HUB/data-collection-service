package com.wangbin.collector.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.application.ConfigDiffCalculator;
import com.wangbin.collector.api.application.ConfigConsoleApplicationService;
import com.wangbin.collector.api.application.ConfigImportExportApplicationService;
import com.wangbin.collector.api.application.LocalDeviceConfigApplicationService;
import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.DeviceConfigBundleRequest;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.manager.ConfigVersionConflictException;
import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.api.exception.ConfigApiExceptionHandler;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.scheduler.DeviceLifecycleCoordinator;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import({ConfigConsoleApplicationService.class,
        LocalDeviceConfigApplicationService.class,
        ConfigImportExportApplicationService.class,
        ConfigDiffCalculator.class,
        SensitiveConfigSanitizer.class})
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConfigManager configManager;

    @MockBean
    private ConfigSyncService configSyncService;

    @MockBean
    private CollectionService collectionService;

    @MockBean
    private DeviceLifecycleCoordinator lifecycleCoordinator;

    @MockBean
    private PointRuntimeStateService pointRuntimeStateService;

    @Test
    void shouldReturnSummary() throws Exception {
        when(configManager.getCacheStats()).thenReturn(Map.of(
                "deviceCount", 1,
                "pointCount", 2,
                "connectionCount", 1,
                "contextCount", 1));
        when(configSyncService.getLastSyncTime()).thenReturn(100L);
        when(configSyncService.getSyncInterval()).thenReturn(1000L);
        when(configSyncService.getServiceId()).thenReturn("collector-1");
        when(configSyncService.getListenerCount()).thenReturn(2);

        mockMvc.perform(get("/api/config/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.data.cacheStats.deviceCount", is(1)))
                .andExpect(jsonPath("$.data.cacheStats.pointCount", is(2)))
                .andExpect(jsonPath("$.data.cacheStats.connectionCount", is(1)))
                .andExpect(jsonPath("$.data.cacheStats.contextCount", is(1)))
                .andExpect(jsonPath("$.data.serviceId", is("collector-1")));
    }

    @Test
    void shouldUpdateDeviceConfig() throws Exception {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("test-device");

        when(configManager.updateDeviceConfig(any(DeviceInfo.class), eq(null))).thenReturn(true);

        mockMvc.perform(put("/api/config/device/dev-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(deviceInfo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")));

        verify(configManager).updateDeviceConfig(argThat(device -> "dev-1".equals(device.getDeviceId())), eq(null));
    }

    @Test
    void shouldImportConfigs() throws Exception {
        ConfigImportRequest request = new ConfigImportRequest();
        ConfigBundle bundle = ConfigBundle.builder()
                .device(new DeviceInfo())
                .build();
        bundle.getDevice().setDeviceId("dev-1");
        request.setBundles(List.of(bundle));
        when(configManager.getConfigurationDeviceSnapshot("dev-1"))
                .thenReturn(new ConfigManager.DeviceConfigurationSnapshot(null, 0L, 0L));

        when(configManager.replaceDeviceContextsAtomically(anyList(), eq(Map.of()))).thenReturn(true);

        mockMvc.perform(post("/api/config/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success", is(1)));
    }

    @Test
    void shouldCreateLocalTemporaryDeviceAndStartFromLocalCache() throws Exception {
        Map<String, Object> request = Map.of(
                "device", Map.of(
                        "id", "local-1",
                        "deviceName", "local-device",
                        "protocolType", "MODBUS_TCP"),
                "connection", Map.of(
                        "deviceId", "local-1",
                        "connectionType", "MODBUS_TCP",
                        "host", "127.0.0.1",
                        "port", 502),
                "points", List.of(Map.of(
                        "deviceId", "local-1",
                        "pointCode", "temperature",
                        "address", "40001",
                        "dataType", "FLOAT")),
                "startAfterSave", true);

        when(configManager.saveLocalDeviceConfigWithResult(any(DeviceInfo.class), any(DeviceConnection.class), anyList(), eq(false), eq(null), eq(null)))
                .thenReturn(new ConfigManager.DeviceConfigCommitResult("local-1", 0L, 1L, 1, 1L));
        when(lifecycleCoordinator.getIntentRevision("local-1")).thenReturn(5L);
        when(lifecycleCoordinator.startDeviceAfterConfigSave("local-1", 5L))
                .thenReturn(new DeviceLifecycleCoordinator.StartAfterConfigSaveResult(true, "ACCEPTED"));

        mockMvc.perform(post("/api/config/local/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.data.configSource", is("local")))
                .andExpect(jsonPath("$.data.temporaryConfig", is(true)))
                .andExpect(jsonPath("$.data.deviceId", is("local-1")))
                .andExpect(jsonPath("$.data.saved", is(true)))
                .andExpect(jsonPath("$.data.changed", is(true)))
                .andExpect(jsonPath("$.data.configVersion", is(1)))
                .andExpect(jsonPath("$.data.startRequested", is(true)))
                .andExpect(jsonPath("$.data.startStatus", is("ACCEPTED")))
                .andExpect(jsonPath("$.data.started", is(true)));

        verify(configManager).saveLocalDeviceConfigWithResult(any(DeviceInfo.class), any(DeviceConnection.class), anyList(), eq(false), eq(null), eq(null));
        verify(lifecycleCoordinator).startDeviceAfterConfigSave("local-1", 5L);
        verify(collectionService, never()).startLocalDevice("local-1");
    }

    @Test
    void bundleHttpCommitReturnsPersistedRevisionAndStaleBaseRevisionIs409() throws Exception {
        DeviceInfo local = device("dev-http");
        local.setConfigSource(ConfigManager.CONFIG_SOURCE_LOCAL);
        local.setTemporaryConfig(true);
        DeviceConnection link = connection("dev-http");
        DataPoint point = point("dev-http");
        point.setPointId("p-http");
        when(configManager.getConfigurationDevice("dev-http")).thenReturn(local);
        when(configManager.isSqlitePersistenceEnabled()).thenReturn(true);
        when(configManager.isLocalTemporaryDevice("dev-http")).thenReturn(true);
        when(configManager.getConfigurationDeviceSnapshot("dev-http"))
                .thenReturn(new ConfigManager.DeviceConfigurationSnapshot(
                        DeviceContext.of(local, link, List.of(point)), 50L, 12L));
        byte[] request = objectMapper.writeValueAsBytes(
                new DeviceConfigBundleRequest(50L, 12L, local, link, List.of(point)));
        when(configManager.replaceDeviceContextAtomically(eq("dev-http"), any(DeviceInfo.class),
                any(DeviceConnection.class), anyList(), eq(50L), eq(12L)))
                .thenReturn(new ConfigManager.DeviceConfigCommitResult("dev-http", 50L, 51L, 1, 13L))
                .thenThrow(new ConfigVersionConflictException("dev-http", 12L, 13L, true));

        mockMvc.perform(get("/api/config/device/dev-http/bundle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion", is(50)))
                .andExpect(jsonPath("$.data.persistedRevision", is(12)));
        mockMvc.perform(put("/api/config/device/dev-http/bundle")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion", is(51)))
                .andExpect(jsonPath("$.data.persistedRevision", is(13)));
        mockMvc.perform(put("/api/config/device/dev-http/bundle")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.machineCode", is("CONFIG_PERSISTENCE_CONFLICT")))
                .andExpect(jsonPath("$.data.expectedRevision", is(12)))
                .andExpect(jsonPath("$.data.currentRevision", is(13)))
                .andExpect(jsonPath("$.data.committed", is(false)));
    }

    @Test
    void partialCrudHttpPassesBaseRevisionAndReturnsConflict() throws Exception {
        DeviceInfo local = device("partial-http");
        local.setConfigSource(ConfigManager.CONFIG_SOURCE_LOCAL);
        local.setTemporaryConfig(true);
        when(configManager.isSqlitePersistenceEnabled()).thenReturn(true);
        when(configManager.isLocalTemporaryDevice("partial-http")).thenReturn(true);
        when(configManager.getConfigurationDevice("partial-http")).thenReturn(local);
        when(configManager.updateDeviceConfig(any(DeviceInfo.class), eq(7L))).thenReturn(true);
        mockMvc.perform(put("/api/config/device/partial-http").param("baseRevision", "7")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(local)))
                .andExpect(status().isOk());
        verify(configManager).updateDeviceConfig(any(DeviceInfo.class), eq(7L));

        when(configManager.updateConnectionConfig(eq("partial-http"), any(DeviceConnection.class), eq(7L)))
                .thenThrow(new ConfigVersionConflictException("partial-http", 7L, 8L, true));
        mockMvc.perform(put("/api/config/device/partial-http/connection").param("baseRevision", "7")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(connection("partial-http"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.machineCode", is("CONFIG_PERSISTENCE_CONFLICT")))
                .andExpect(jsonPath("$.data.currentRevision", is(8)));
    }

    @Test
    void shouldRejectDeleteForNonLocalDevice() throws Exception {
        when(configManager.isLocalTemporaryDevice("remote-1")).thenReturn(false);

        mockMvc.perform(delete("/api/config/local/device/remote-1")
                        .requestAttr(RequestCorrelationFilter.ATTR_REQUEST_ID, "config-400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.machineCode", is("CONFIG_OPERATION_FAILED")))
                .andExpect(jsonPath("$.extra.requestId", is("config-400")));
    }

    @Test
    void shouldReturnNotFoundWhenConnectionDeviceDoesNotExist() throws Exception {
        when(configManager.containsDevice("missing")).thenReturn(false);

        mockMvc.perform(get("/api/config/device/missing/connection"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")));
    }

    @Test
    void configConflictIncludesStatusCodeAndRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "config-409");
        var response = new ConfigApiExceptionHandler().handleConfigApiException(
                new ConfigApiException(HttpStatus.CONFLICT, "配置版本冲突",
                        Map.of("deviceId", "dev-1", "expectedVersion", 1, "currentVersion", 2)), request);
        var json = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(json.path("code").asInt()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(json.path("status").asText()).isEqualTo("error");
        org.assertj.core.api.Assertions.assertThat(json.path("machineCode").asText())
                .isEqualTo("CONFIG_VERSION_CONFLICT");
        org.assertj.core.api.Assertions.assertThat(json.path("data").path("currentVersion").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(json.path("extra").path("requestId").asText())
                .isEqualTo("config-409");
    }

    private DeviceInfo device(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("local-device");
        device.setProtocolType("MODBUS_TCP");
        return device;
    }

    private DeviceConnection connection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        return connection;
    }

    private DataPoint point(String deviceId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointCode("temperature");
        point.setAddress("40001");
        point.setDataType("FLOAT");
        return point;
    }
}
