package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.application.ConfigConsoleApplicationService;
import com.wangbin.collector.api.controller.ConfigController;
import com.wangbin.collector.core.config.manager.ConfigVersionConflictException;
import com.wangbin.collector.core.config.persistence.ConfigurationConflictException;
import com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.*;

class WangbinPhase2ConfigApiErrorTest {
    private final ConfigConsoleApplicationService service = mock(ConfigConsoleApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(service))
            .setControllerAdvice(new ConfigApiExceptionHandler()).build();

    @Test void sqliteConflictIsActual409WithSeparateDimension() throws Exception {
        doThrow(new WangbinConfigurationPersistenceException(CONFLICT,List.of("d"))).when(service).deleteLocalDevice("d", null);
        mvc.perform(delete("/api/config/local/device/d")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.dimension").value("PERSISTENCE_REVISION"))
                .andExpect(jsonPath("$.data.committed").value(false))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.deviceIds[0]").value("d"))
                .andExpect(jsonPath("$.data.currentVersion").doesNotExist());
    }

    @Test void committedOutcomesReturnCommittedSuccessWithoutPretendingRollback() throws Exception {
        for (var outcome : List.of(COMMITTED_RECONCILED, COMMITTED_RECOVERY_REQUIRED, COMMITTED_APPLY_FAILED)) {
            doThrow(new WangbinConfigurationPersistenceException(outcome, List.of("d")))
                    .when(service).deleteLocalDevice("d", null);
            mvc.perform(delete("/api/config/local/device/d")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.data.committed").value(true))
                    .andExpect(jsonPath("$.data.reconciled").value(outcome == COMMITTED_RECONCILED))
                    .andExpect(jsonPath("$.data.reloadRequired").value(true))
                    .andExpect(jsonPath("$.machineCode").value("CONFIG_" + outcome.name()))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("回滚"))));
        }
    }

    @Test void stalePersistedRevisionReturns409WithRealRevisionDimension() throws Exception {
        doThrow(new ConfigVersionConflictException("d", 4, 5, true))
                .when(service).deleteLocalDevice("d", 4L);
        mvc.perform(delete("/api/config/local/device/d").param("baseRevision", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.machineCode").value("CONFIG_PERSISTENCE_CONFLICT"))
                .andExpect(jsonPath("$.data.expectedRevision").value(4))
                .andExpect(jsonPath("$.data.currentRevision").value(5))
                .andExpect(jsonPath("$.data.dimension").value("PERSISTENCE_REVISION"))
                .andExpect(jsonPath("$.data.committed").value(false))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.data.currentVersion").doesNotExist());
    }

    @Test void commitUnconfirmedIs503WithoutClaimingCommitOrRollback() throws Exception {
        doThrow(new WangbinConfigurationPersistenceException(COMMIT_UNCONFIRMED, List.of("d")))
                .when(service).deleteLocalDevice("d", null);
        mvc.perform(delete("/api/config/local/device/d"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.machineCode").value("CONFIG_COMMIT_UNCONFIRMED"))
                .andExpect(jsonPath("$.data.committed").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.reloadRequired").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("回滚"))));
    }

    @Test void writeFailureIs503AndPhaseOneConflictStill409() throws Exception {
        doThrow(new WangbinConfigurationPersistenceException(WRITE_FAILED,List.of("d"))).when(service).deleteLocalDevice("d", null);
        mvc.perform(delete("/api/config/local/device/d")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.committed").value(false));
        doThrow(new ConfigurationConflictException()).when(service).deleteLocalDevice("d", null);
        mvc.perform(delete("/api/config/local/device/d")).andExpect(status().isConflict());
    }

    @Test void malformedJsonHasFixedSafeMessage() throws Exception {
        mvc.perform(put("/api/config/device/d/connection").contentType("application/json")
                        .content("{\"password\":\"test-only-secret\",\"port\":\"bad-secret-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("配置 JSON 格式不正确，请检查字段类型和结构"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only-secret"))));
    }

    @Test void invalidProtocolAndPartialUpdateAreActual400WithoutLeakingValues() throws Exception {
        doThrow(com.wangbin.collector.common.exception.CollectorException.configException(
                "invalid-[REDACTED]", "d", null)).when(service).getDeviceConnection("d");
        mvc.perform(get("/api/config/device/d/connection")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.machineCode").value("CONFIG_OPERATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-[REDACTED]"))));
        doThrow(new IllegalArgumentException("invalid-[REDACTED]")).when(service).getDeviceConnection("d");
        mvc.perform(get("/api/config/device/d/connection")).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-[REDACTED]"))));
    }

    @Test void localEditVersionConflictIsActual409() throws Exception {
        doThrow(new com.wangbin.collector.core.config.manager.ConfigVersionConflictException("d", 7, 8))
                .when(service).getLocalDevice("d");
        mvc.perform(get("/api/config/local/device/d")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.machineCode").value("CONFIG_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.expectedVersion").value(7))
                .andExpect(jsonPath("$.data.currentVersion").value(8));
    }
}
