package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.DeviceConsoleApplicationService;
import com.wangbin.collector.core.collector.CollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
@Import(DeviceConsoleApplicationService.class)
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CollectionService collectionService;

    @Test
    void shouldStartDeviceWithLegacyDeviceEnvelopeFields() throws Exception {
        when(collectionService.startDevice("dev-1")).thenReturn(true);

        mockMvc.perform(post("/api/device/dev-1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.message", is("设备启动成功")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldReturnDeviceStartFailureWithLegacyDeviceEnvelopeFields() throws Exception {
        when(collectionService.startDevice("dev-1")).thenReturn(false);

        mockMvc.perform(post("/api/device/dev-1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.message", is("设备已启动或启动失败")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")));
    }

    @Test
    void shouldReturnRunningDevicesWithCount() throws Exception {
        when(collectionService.getRunningDevices()).thenReturn(List.of("dev-1", "dev-2"));

        mockMvc.perform(get("/api/device/running"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.data[0]", is("dev-1")))
                .andExpect(jsonPath("$.data[1]", is("dev-2")))
                .andExpect(jsonPath("$.count", is(2)));
    }

    @Test
    void shouldReturnRunningFlagAtTopLevel() throws Exception {
        when(collectionService.isDeviceRunning("dev-1")).thenReturn(true);

        mockMvc.perform(get("/api/device/dev-1/running"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.running", is(true)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
