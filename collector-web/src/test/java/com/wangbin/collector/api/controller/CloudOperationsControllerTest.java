package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.CloudOperationsApplicationService;
import com.wangbin.collector.api.controller.dto.CloudFlushResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxDetailResponse;
import com.wangbin.collector.api.controller.dto.CloudOutboxItemResponse;
import com.wangbin.collector.api.controller.dto.CloudTestResponse;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CloudOperationsController.class)
class CloudOperationsControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private CloudOperationsApplicationService service;

    @Test
    void shouldExposeBoundedListParametersAndLightweightRows() throws Exception {
        when(service.list(CloudOutboxStatus.ISOLATED, "dev-1", 50)).thenReturn(List.of(
                CloudOutboxItemResponse.builder().messageId("msg-1").localDeviceId("dev-1")
                        .status(CloudOutboxStatus.ISOLATED).createdAt(100L).nextAttemptAt(200L).build()));

        mockMvc.perform(get("/api/cloud/outbox").param("status", "ISOLATED")
                        .param("deviceId", "dev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].messageId", is("msg-1")))
                .andExpect(jsonPath("$.data[0].createdAt", is(100)))
                .andExpect(jsonPath("$.data[0].nextAttemptAt", is(200)));
        verify(service).list(CloudOutboxStatus.ISOLATED, "dev-1", 50);
    }

    @Test
    void shouldReturnMissingDetailAsErrorEnvelope() throws Exception {
        when(service.detail("missing")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/cloud/outbox/missing"))
                .andExpect(jsonPath("$.code", is(404)));
    }

    @Test
    void shouldReturnFullDetailFromService() throws Exception {
        when(service.detail("msg-1")).thenReturn(Optional.of(CloudOutboxDetailResponse.builder()
                .summary(CloudOutboxItemResponse.builder().messageId("msg-1").build())
                .shadowVersion(7).windowStart(100).windowEnd(200).commits(List.of()).build()));
        mockMvc.perform(get("/api/cloud/outbox/msg-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.messageId", is("msg-1")))
                .andExpect(jsonPath("$.data.shadowVersion", is(7)));
    }

    @Test
    void shouldReportFlushAcceptanceWithoutPromisingAck() throws Exception {
        when(service.flush()).thenReturn(CloudFlushResponse.builder().accepted(true)
                .pendingBefore(4).isolated(2).triggeredAt(123L).build());
        mockMvc.perform(post("/api/cloud/flush"))
                .andExpect(jsonPath("$.data.accepted", is(true)))
                .andExpect(jsonPath("$.data.pendingBefore", is(4)))
                .andExpect(jsonPath("$.data.triggeredAt", is(123)));
    }

    @Test
    void shouldAllowUnknownConnectionStatusInTestResult() throws Exception {
        when(service.test()).thenReturn(CloudTestResponse.builder().enabled(true).configured(true)
                .connected(null).message("无法证明实时连接").checkedAt(123L).build());
        mockMvc.perform(post("/api/cloud/test"))
                .andExpect(jsonPath("$.data.message", is("无法证明实时连接")))
                .andExpect(jsonPath("$.data.checkedAt", is(123)));
    }
}
