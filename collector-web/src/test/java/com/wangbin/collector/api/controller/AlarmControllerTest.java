package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.AlarmLifecycleApplicationService;
import com.wangbin.collector.api.controller.dto.AlarmLifecycleResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
}
