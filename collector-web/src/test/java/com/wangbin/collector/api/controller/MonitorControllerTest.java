package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.ConsoleRuntimeStatusApplicationService;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.monitor.metrics.CacheMonitorService;
import com.wangbin.collector.monitor.metrics.CloudReportMonitorService;
import com.wangbin.collector.monitor.metrics.DeviceMonitorService;
import com.wangbin.collector.monitor.metrics.ExceptionMonitorService;
import com.wangbin.collector.monitor.metrics.PerformanceMonitorService;
import com.wangbin.collector.monitor.metrics.PipelineBackpressureMonitorService;
import com.wangbin.collector.monitor.metrics.PipelineBackpressureSnapshot;
import com.wangbin.collector.monitor.metrics.PipelineStatus;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.TdengineMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitorControllerTest {

    @Test
    void shouldReturnCloudReportMetricsWithStableDtoAndDynamicHandlerMaps() throws Exception {
        CloudReportMonitorService cloudReportMonitorService = mock(CloudReportMonitorService.class);
        when(cloudReportMonitorService.getCloudReportMetrics()).thenReturn(cloudReportMetrics());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MonitorController(
                mock(CacheMonitorService.class),
                mock(DeviceMonitorService.class),
                mock(PerformanceMonitorService.class),
                mock(SystemResourceMonitorService.class),
                mock(ExceptionMonitorService.class),
                cloudReportMonitorService,
                mock(PipelineBackpressureMonitorService.class),
                mock(TdengineMonitorService.class),
                mock(CollectionScheduler.class),
                mock(ConsoleRuntimeStatusApplicationService.class))).build();

        mockMvc.perform(get("/monitor/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.status", is("OK")))
                .andExpect(jsonPath("$.supportedProtocols[0]", is("MQTT")))
                .andExpect(jsonPath("$.handlersStatus.MQTT.enabled", is(true)))
                .andExpect(jsonPath("$.handlersStatistics.MQTT.clientManager.connectedClients", is(1)))
                .andExpect(jsonPath("$.configured.cloudTargetKeys[0]", is("pk/device")))
                .andExpect(jsonPath("$.configured.cloudTargetCoverage", is(0.6D)))
                .andExpect(jsonPath("$.executor.queueUsage", is(0.1D)))
                .andExpect(jsonPath("$.batch.maxDelayMs", is(1000)))
                .andExpect(jsonPath("$.ack.commitOn", is("publish-success")))
                .andExpect(jsonPath("$.outbox.pendingCount", is(3)))
                .andExpect(jsonPath("$.payload.includeMessageId", is(true)))
                .andExpect(jsonPath("$.risks[0]", is("风险提示")))
                .andExpect(jsonPath("$.generatedAt", is(123456)));
    }

    @Test
    void shouldReturnPipelineBackpressureSnapshot() throws Exception {
        PipelineBackpressureMonitorService pipelineService = mock(PipelineBackpressureMonitorService.class);
        when(pipelineService.getSnapshot()).thenReturn(pipelineSnapshot());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MonitorController(
                mock(CacheMonitorService.class),
                mock(DeviceMonitorService.class),
                mock(PerformanceMonitorService.class),
                mock(SystemResourceMonitorService.class),
                mock(ExceptionMonitorService.class),
                mock(CloudReportMonitorService.class),
                pipelineService,
                mock(TdengineMonitorService.class),
                mock(CollectionScheduler.class),
                mock(ConsoleRuntimeStatusApplicationService.class))).build();

        mockMvc.perform(get("/monitor/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("HEALTHY")))
                .andExpect(jsonPath("$.generatedAt", is(1788998400000L)))
                .andExpect(jsonPath("$.ingress.status", is("HEALTHY")))
                .andExpect(jsonPath("$.stream.status", is("HEALTHY")))
                .andExpect(jsonPath("$.history.status", is("DISABLED")))
                .andExpect(jsonPath("$.cloud.status", is("DISABLED")))
                .andExpect(jsonPath("$.executors.cache.queueCapacity", is(100)))
                .andExpect(jsonPath("$.risks[0]", is("INGRESS_LOCAL_QUEUE_HIGH")));
    }

    private PipelineBackpressureSnapshot pipelineSnapshot() {
        PipelineBackpressureSnapshot.IngressSnapshot ingress = new PipelineBackpressureSnapshot.IngressSnapshot(
                true, PipelineStatus.HEALTHY, 0L, 0L, 0L, 1, 100, 0.01D,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        PipelineBackpressureSnapshot.StreamSnapshot stream = new PipelineBackpressureSnapshot.StreamSnapshot(
                true, PipelineStatus.HEALTHY, 1, 1, 100, 0.01D,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        PipelineBackpressureSnapshot.HistorySnapshot history = new PipelineBackpressureSnapshot.HistorySnapshot(
                false, PipelineStatus.DISABLED, 0L, 0L, 0L, 0, 100, 0D,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0D);
        PipelineBackpressureSnapshot.CloudSnapshot cloud = new PipelineBackpressureSnapshot.CloudSnapshot(
                false, PipelineStatus.DISABLED, 0L, 0L, 0L);
        PipelineBackpressureSnapshot.ExecutorSnapshot executor = new PipelineBackpressureSnapshot.ExecutorSnapshot(
                "telemetryCacheStageExecutor", PipelineStatus.HEALTHY, 1, 1, 0, 0, 100, 0D, 0L, 0L);
        return new PipelineBackpressureSnapshot(PipelineStatus.HEALTHY, 1788998400000L,
                ingress, stream, history, cloud, Map.of("cache", executor), List.of("INGRESS_LOCAL_QUEUE_HIGH"));
    }

    private Map<String, Object> cloudReportMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("enabled", true);
        metrics.put("status", "OK");
        metrics.put("statusText", "云上报链路已连接");
        metrics.put("mode", "MQTT");
        metrics.put("cloudProvider", "aliyun");
        metrics.put("supportedProtocols", List.of("MQTT", "HTTP"));
        metrics.put("handlersStatus", Map.of("MQTT", Map.of("enabled", true)));
        metrics.put("handlersStatistics", Map.of("MQTT", Map.of("clientManager", Map.of("connectedClients", 1))));
        metrics.put("configured", configured());
        metrics.put("executor", executor());
        metrics.put("batch", Map.of(
                "enabled", true,
                "maxDevicesPerPack", 50,
                "maxPropertiesPerPack", 500,
                "maxPayloadBytes", 131072,
                "maxDelayMs", 1000L,
                "highPriorityBypass", true));
        metrics.put("ack", Map.of(
                "mode", "async",
                "timeoutMs", 5000L,
                "maxPending", 10000,
                "timeoutScanMs", 500L,
                "commitOn", "publish-success"));
        metrics.put("outbox", Map.of(
                "enabled", true,
                "pendingCount", 3L,
                "isolatedCount", 1L,
                "oldestMessageAgeMs", 100L));
        metrics.put("payload", Map.of(
                "profile", "compact",
                "includeQuality", "on_error",
                "includePropertyTs", false,
                "includeMetadata", false,
                "includeMessageId", true));
        metrics.put("risks", List.of("风险提示"));
        metrics.put("generatedAt", 123456L);
        return metrics;
    }

    private Map<String, Object> configured() {
        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put("configSnapshotAvailable", true);
        configured.put("deviceCount", 2);
        configured.put("pointCount", 5);
        configured.put("reportEnabledPointCount", 4);
        configured.put("eventEnabledPointCount", 1);
        configured.put("changeTriggerPointCount", 1);
        configured.put("reportFieldPointCount", 4);
        configured.put("reportablePointCount", 3);
        configured.put("cloudTargetDeviceCount", 1);
        configured.put("invalidCloudTargetDeviceCount", 1);
        configured.put("cloudTargetCount", 1);
        configured.put("cloudTargetKeys", List.of("pk/device"));
        configured.put("cloudTargetCoverage", 0.6D);
        return configured;
    }

    private Map<String, Object> executor() {
        Map<String, Object> executor = new LinkedHashMap<>();
        executor.put("type", "ThreadPoolExecutor");
        executor.put("corePoolSize", 1);
        executor.put("maxPoolSize", 2);
        executor.put("poolSize", 1);
        executor.put("activeCount", 0);
        executor.put("queueSize", 1);
        executor.put("queueRemainingCapacity", 9);
        executor.put("queueCapacity", 10);
        executor.put("queueUsage", 0.1D);
        executor.put("completedTaskCount", 10L);
        executor.put("taskCount", 11L);
        executor.put("rejectedCount", 0L);
        return executor;
    }
}
