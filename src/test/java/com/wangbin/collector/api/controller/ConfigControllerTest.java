package com.wangbin.collector.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
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

    @Test
    void shouldReturnSummary() throws Exception {
        when(configManager.getCacheStats()).thenReturn(Map.of("deviceCount", 1));
        when(configSyncService.getLastSyncTime()).thenReturn(100L);
        when(configSyncService.getSyncInterval()).thenReturn(1000L);
        when(configSyncService.getServiceId()).thenReturn("collector-1");
        when(configSyncService.getListenerCount()).thenReturn(2);

        mockMvc.perform(get("/api/config/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.data.cacheStats.deviceCount", is(1)))
                .andExpect(jsonPath("$.data.serviceId", is("collector-1")));
    }

    @Test
    void shouldUpdateDeviceConfig() throws Exception {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("test-device");

        when(configManager.updateDeviceConfig(any(DeviceInfo.class))).thenReturn(true);

        mockMvc.perform(put("/api/config/device/dev-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(deviceInfo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")));

        verify(configManager).updateDeviceConfig(argThat(device -> "dev-1".equals(device.getDeviceId())));
    }

    @Test
    void shouldImportConfigs() throws Exception {
        ConfigImportRequest request = new ConfigImportRequest();
        ConfigBundle bundle = ConfigBundle.builder()
                .device(new DeviceInfo())
                .build();
        bundle.getDevice().setDeviceId("dev-1");
        request.setBundles(List.of(bundle));

        when(configManager.updateDeviceConfig(any(DeviceInfo.class))).thenReturn(true);
        when(configManager.updateDataPoints(eq("dev-1"), anyList())).thenReturn(true);

        mockMvc.perform(post("/api/config/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success", is(1)));
    }
}
