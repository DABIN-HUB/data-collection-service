package com.wangbin.collector.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.report.shadow.ShadowManager;
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
        when(shadowManager.getShadowDocument("dev-1")).thenReturn(Map.of(
                "deviceId", "dev-1",
                "state", Map.of("reported", Map.of("temperature", 25))
        ));

        mockMvc.perform(get("/api/shadow/dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.deviceId", is("dev-1")))
                .andExpect(jsonPath("$.data.state.reported.temperature", is(25)));
    }

    @Test
    void shouldUpdateDesiredFromProperties() throws Exception {
        when(shadowManager.updateDesired(eq("dev-1"),
                argThat(map -> map.containsKey("temperature")),
                eq("unit-test"),
                eq(null)))
                .thenReturn(Map.of("deviceId", "dev-1"));

        mockMvc.perform(post("/api/shadow/dev-1/desired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "source", "unit-test",
                                "properties", Map.of("temperature", 26)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        verify(shadowManager).updateDesired(eq("dev-1"),
                argThat(map -> Integer.valueOf(26).equals(map.get("temperature"))),
                eq("unit-test"),
                eq(null));
    }

    @Test
    void shouldClearDesiredFields() throws Exception {
        when(shadowManager.clearDesired("dev-1", List.of("temperature")))
                .thenReturn(Map.of("deviceId", "dev-1"));

        mockMvc.perform(delete("/api/shadow/dev-1/desired")
                        .param("fields", "temperature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        verify(shadowManager).clearDesired("dev-1", List.of("temperature"));
    }
}
