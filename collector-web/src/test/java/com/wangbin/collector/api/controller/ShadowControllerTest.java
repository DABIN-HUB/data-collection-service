package com.wangbin.collector.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShadowController.class)
class ShadowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ShadowManager shadowManager;

    @Test
    void shouldReturnShadowDocument() throws Exception {
        when(shadowManager.getShadowDocument("dev-1")).thenReturn(shadowDocument());

        mockMvc.perform(get("/api/shadow/dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.data.version", is(3)))
                .andExpect(jsonPath("$.data.state.reported.temperature", is(25)))
                .andExpect(jsonPath("$.data.state.desired.temperature", is(26)))
                .andExpect(jsonPath("$.data.state.delta.fan", is("ON")))
                .andExpect(jsonPath("$.data.metadata.reported.temperature.quality", is("GOOD")));
    }

    @Test
    void shouldReturnShadowDeltaAndKeepDynamicPropertyKeys() throws Exception {
        when(shadowManager.getShadowDelta("dev-1")).thenReturn(Map.of(
                "deviceId", "dev-1",
                "version", 3L,
                "timestamp", 100L,
                "delta", Map.of("fan", "ON"),
                "metadata", Map.of("fan", Map.of("source", "api"))));

        mockMvc.perform(get("/api/shadow/dev-1/delta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.data.delta.fan", is("ON")))
                .andExpect(jsonPath("$.data.metadata.fan.source", is("api")));
    }

    @Test
    void shouldUpdateDesiredFromProperties() throws Exception {
        when(shadowManager.updateDesired(eq("dev-1"),
                argThat(map -> map.containsKey("temperature")),
                eq("unit-test"),
                eq(null)))
                .thenReturn(shadowDocument());

        mockMvc.perform(post("/api/shadow/dev-1/desired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "source", "unit-test",
                                "properties", Map.of("temperature", 26)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.state.desired.temperature", is(26)));

        verify(shadowManager).updateDesired(eq("dev-1"),
                argThat(map -> Integer.valueOf(26).equals(map.get("temperature"))),
                eq("unit-test"),
                eq(null));
    }

    @Test
    void shouldClearDesiredFields() throws Exception {
        when(shadowManager.clearDesired("dev-1", List.of("temperature"), null))
                .thenReturn(shadowDocument());

        mockMvc.perform(delete("/api/shadow/dev-1/desired")
                        .param("fields", "temperature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.state.reported.temperature", is(25)));

        verify(shadowManager).clearDesired("dev-1", List.of("temperature"), null);
    }

    @Test
    void shouldReturnConflictWithVersionsOnStaleUpdate() throws Exception {
        when(shadowManager.updateDesired(eq("dev-1"), argThat(map -> map.containsKey("temperature")),
                eq("api"), eq(2L)))
                .thenThrow(new ShadowManager.ShadowVersionConflictException(2L, 3L));

        mockMvc.perform(post("/api/shadow/dev-1/desired")
                        .requestAttr(RequestCorrelationFilter.ATTR_REQUEST_ID, "shadow-409")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "expectedVersion", 2, "properties", Map.of("temperature", 26)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)))
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.machineCode", is("SHADOW_VERSION_CONFLICT")))
                .andExpect(jsonPath("$.extra.requestId", is("shadow-409")))
                .andExpect(jsonPath("$.data.expectedVersion", is(2)))
                .andExpect(jsonPath("$.data.currentVersion", is(3)));
    }

    @Test
    void shouldReturnConflictWithVersionsOnStaleClear() throws Exception {
        when(shadowManager.clearDesired("dev-1", List.of("temperature"), 2L))
                .thenThrow(new ShadowManager.ShadowVersionConflictException(2L, 3L));

        mockMvc.perform(delete("/api/shadow/dev-1/desired")
                        .param("fields", "temperature").param("expectedVersion", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.expectedVersion", is(2)))
                .andExpect(jsonPath("$.data.currentVersion", is(3)));
    }

    private Map<String, Object> shadowDocument() {
        return Map.of(
                "deviceId", "dev-1",
                "version", 3L,
                "timestamp", 100L,
                "createdAt", 10L,
                "lastReportAt", 90L,
                "lastWindowStart", 70L,
                "lastWindowEnd", 80L,
                "state", Map.of(
                        "reported", Map.of("temperature", 25),
                        "desired", Map.of("temperature", 26),
                        "delta", Map.of("fan", "ON"),
                        "lastReported", Map.of("temperature", 24)),
                "metadata", Map.of(
                        "reported", Map.of("temperature", Map.of("quality", "GOOD")),
                        "desired", Map.of("temperature", Map.of("source", "api"))));
    }
}
