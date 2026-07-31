package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cloud.model.CloudTargetConfig;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.service.ReportManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudReportMonitorServiceTest {

    private final ReportManager reportManager = mock(ReportManager.class);
    private final CloudOutboxService cloudOutboxService = mock(CloudOutboxService.class);
    private final ConfigManager configManager = mock(ConfigManager.class);
    private final ReportProperties properties = new ReportProperties();
    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));
        when(configManager.getAllDeviceContexts()).thenReturn(List.of(configuredContext()));
        when(reportManager.getSupportedProtocols()).thenReturn(List.of("MQTT", "HTTP"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldReportErrorWhenMqttTargetHasNoConnectedClient() {
        properties.setMode("MQTT");
        mockHandler("MQTT", 0);

        Map<String, Object> metrics = service().getCloudReportMetrics();

        assertEquals("ERROR", metrics.get("status"));
    }

    @Test
    void shouldReportOkWhenMqttTargetIsConnected() {
        properties.setMode("MQTT");
        mockHandler("MQTT", 1);

        Map<String, Object> metrics = service().getCloudReportMetrics();

        assertEquals("OK", metrics.get("status"));
    }

    @Test
    void shouldReportReadyForStatelessHttpHandler() {
        properties.setMode("HTTP");
        when(reportManager.getHandlersStatus()).thenReturn(Map.of("HTTP", Map.of("enabled", true)));
        when(reportManager.getHandlersStatistics()).thenReturn(Map.of("HTTP", Map.of()));

        Map<String, Object> metrics = service().getCloudReportMetrics();

        assertEquals("READY", metrics.get("status"));
    }

    private CloudReportMonitorService service() {
        return new CloudReportMonitorService(properties, reportManager, cloudOutboxService, configManager, executor);
    }

    private void mockHandler(String protocol, int connectedClients) {
        when(reportManager.getHandlersStatus()).thenReturn(Map.of(protocol, Map.of("enabled", true)));
        when(reportManager.getHandlersStatistics()).thenReturn(Map.of(
                protocol,
                Map.of("clientManager", Map.of("connectedClients", connectedClients))
        ));
    }

    private DeviceContext configuredContext() {
        CloudTargetConfig cloudTarget = new CloudTargetConfig();
        cloudTarget.setEnabled(true);
        cloudTarget.setProductKey("test-product");
        cloudTarget.setDeviceName("test-device");

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("local-device-01");
        device.setCloudTarget(cloudTarget);

        DataPoint point = new DataPoint();
        point.setPointId("temperature");
        point.setAdditionalConfig(Map.of("reportEnabled", true, "reportField", "temperature"));
        return DeviceContext.of(device, null, List.of(point));
    }
}
