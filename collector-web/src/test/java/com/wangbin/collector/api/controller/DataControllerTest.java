package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.RealtimeDataApplicationService;
import com.wangbin.collector.api.controller.dto.AdaptiveResetResponse;
import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataController.class)
class DataControllerTest {

    private final MockMvc mockMvc;

    @MockBean
    private RealtimeDataApplicationService realtimeDataApplicationService;

    @Autowired
    DataControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldBindSinglePointRouteAndSerializeResponse() throws Exception {
        when(realtimeDataApplicationService.getPointData("dev-1", "p-1"))
                .thenReturn(PointRealtimeResponse.builder()
                        .status("success")
                        .deviceId("dev-1")
                        .pointId("p-1")
                        .data(PointRealtimePayload.builder()
                                .pointId("p-1")
                                .value(12.3D)
                                .build())
                        .timestamp(1000L)
                        .build());

        mockMvc.perform(get("/api/data/device/dev-1/point/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.pointId", is("p-1")))
                .andExpect(jsonPath("$.data.pointId", is("p-1")))
                .andExpect(jsonPath("$.data.value", is(12.3D)));

        verify(realtimeDataApplicationService).getPointData("dev-1", "p-1");
    }

    @Test
    void shouldBindDeviceDataPointIdsFilter() throws Exception {
        Map<String, PointRealtimePayload> data = new LinkedHashMap<>();
        data.put("p-1", PointRealtimePayload.builder().pointId("p-1").value("v1").build());
        data.put("p-2", PointRealtimePayload.builder().pointId("p-2").value("v2").build());
        when(realtimeDataApplicationService.getDeviceData(eq("dev-1"), eq(List.of("p-1", "p-2"))))
                .thenReturn(DeviceRealtimeDataResponse.builder()
                        .status("success")
                        .deviceId("dev-1")
                        .dataCount(2)
                        .data(data)
                        .timestamp(1000L)
                        .build());

        mockMvc.perform(get("/api/data/device/dev-1")
                        .param("pointIds", "p-1", "p-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.dataCount", is(2)))
                .andExpect(jsonPath("$.data.p-1.value", is("v1")))
                .andExpect(jsonPath("$.data.p-2.value", is("v2")));

        verify(realtimeDataApplicationService).getDeviceData("dev-1", List.of("p-1", "p-2"));
    }

    @Test
    void shouldBindPointHistoryQueryParameters() throws Exception {
        List<Map<String, Object>> rows = List.of(Map.of("value", 12.3D));
        when(realtimeDataApplicationService.getPointHistory("dev-1", "p-1", 100L, 200L, 10))
                .thenReturn(HistoryDataResponse.builder()
                        .status("success")
                        .deviceId("dev-1")
                        .pointId("p-1")
                        .count(1)
                        .data(rows)
                        .startTs(100L)
                        .endTs(200L)
                        .timestamp(1000L)
                        .build());

        mockMvc.perform(get("/api/data/history/device/dev-1/point/p-1")
                        .param("startTs", "100")
                        .param("endTs", "200")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.pointId", is("p-1")))
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.startTs", is(100)))
                .andExpect(jsonPath("$.endTs", is(200)));

        verify(realtimeDataApplicationService).getPointHistory("dev-1", "p-1", 100L, 200L, 10);
    }

    @Test
    void shouldBindRecentAlarmFilters() throws Exception {
        when(realtimeDataApplicationService.getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10))
                .thenReturn(AlarmHistoryDataResponse.builder()
                        .status("success")
                        .deviceId("dev-1")
                        .pointId("p-1")
                        .pointCode("temperature")
                        .level("HIGH")
                        .ruleId("rule-1")
                        .count(1)
                        .total(20L)
                        .data(List.of(Map.of("alarm", "a")))
                        .startTs(100L)
                        .endTs(200L)
                        .timestamp(1000L)
                        .build());

        mockMvc.perform(get("/api/data/history/alarms")
                        .param("deviceId", "dev-1")
                        .param("pointId", "p-1")
                        .param("pointCode", "temperature")
                        .param("level", "HIGH")
                        .param("ruleId", "rule-1")
                        .param("startTs", "100")
                        .param("endTs", "200")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.pointCode", is("temperature")))
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.total", is(20)));

        verify(realtimeDataApplicationService).getRecentAlarmHistory(
                "dev-1", "p-1", "temperature", "HIGH", "rule-1", 100L, 200L, 10);
    }

    @Test
    void shouldBindResetAdaptivePostRoute() throws Exception {
        when(realtimeDataApplicationService.resetAdaptiveConfig("dev-1"))
                .thenReturn(AdaptiveResetResponse.builder()
                        .code(200)
                        .message("重置成功")
                        .build());

        mockMvc.perform(post("/api/data/device/dev-1/reset-adaptive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.message", is("重置成功")));

        verify(realtimeDataApplicationService).resetAdaptiveConfig("dev-1");
    }
}
