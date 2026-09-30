package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.AlarmLifecycleApplicationService;
import com.wangbin.collector.api.controller.dto.AlarmLifecycleResponse;
import com.wangbin.collector.api.exception.AlarmQueryValidationException;
import com.wangbin.collector.api.exception.ApiValidationExceptionHandler;
import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlarmControllerTest {
    @Test
    void queryForwardsLifecycleFilters() {
        AlarmLifecycleApplicationService service = mock(AlarmLifecycleApplicationService.class);
        var response = new AlarmLifecycleResponse("success", List.of(), 0);
        when(service.query("device-1", "p-1", "code", "rule", "WARNING", "ACKED", 100L, 200L, 20))
                .thenReturn(response);
        var controller = new AlarmController(service);
        assertThat(controller.query("device-1", "p-1", "code", "rule", "WARNING", "ACKED", 100L, 200L, 20)
                .getData()).isSameAs(response);
    }

    @Test
    void invalidAlarmStateHasExplicitValidationContract() throws Exception {
        AlarmLifecycleApplicationService service = mock(AlarmLifecycleApplicationService.class);
        when(service.query(null, null, null, null, null, "UNKNOWN", null, null, null))
                .thenThrow(new AlarmQueryValidationException("告警状态仅支持 ACTIVE、ACKED、RECOVERED"));
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmController(service))
                .setControllerAdvice(new ApiValidationExceptionHandler()).build();

        mvc.perform(get("/api/alarms").param("state", "UNKNOWN")
                        .requestAttr(RequestCorrelationFilter.ATTR_REQUEST_ID, "alarm-400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.machineCode", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.extra.requestId", is("alarm-400")));
    }
}
