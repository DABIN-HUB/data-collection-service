package com.wangbin.collector.core.report.downlink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cloud.ota.CloudOtaService;
import com.wangbin.collector.core.cloud.register.CloudSubDeviceRegisterService;
import com.wangbin.collector.core.cloud.topology.CloudTopologyService;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqttDownlinkServiceCloudTest {

    @Test
    void shouldCreateOtaTaskFromStandardTopic() {
        MqttDownlinkService service = service();
        ReflectionTestUtils.setField(service, "cloudOtaService", new CloudOtaService());

        MqttDownlinkResult result = service.handle(
                "/sys/pk-gw/gateway-1/thing/ota/upgrade",
                json("{\"id\":\"ota-1\",\"params\":{\"version\":\"1.2.3\",\"fileUrl\":\"https://fw.bin\",\"fileSize\":1024}}"));

        assertEquals(0, result.getCode());
        assertEquals("gateway-1", result.getDeviceId());
        assertEquals("1.2.3", result.getData().get("version"));
        assertEquals("https://fw.bin", result.getData().get("fileUrl"));
    }

    @Test
    void shouldApplyTopologyAddFromStandardTopic() {
        MqttDownlinkService service = service();
        ReflectionTestUtils.setField(service, "cloudTopologyService", new CloudTopologyService());

        MqttDownlinkResult result = service.handle(
                "/sys/pk-gw/gateway-1/thing/topo/add",
                json("{\"id\":\"topo-1\",\"params\":{\"subList\":[{\"productKey\":\"pk-sub\",\"deviceName\":\"sub-1\"}]}}"));

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().get("changed"));
        assertEquals(1, result.getData().get("currentCount"));
    }

    @Test
    void shouldStoreSubDeviceRegisterReply() {
        MqttDownlinkService service = service();
        ReflectionTestUtils.setField(service, "cloudSubDeviceRegisterService", new CloudSubDeviceRegisterService());

        MqttDownlinkResult result = service.handle(
                "/sys/pk-gw/gateway-1/thing/auth/register/sub_reply",
                json("{\"id\":\"reg-1\",\"data\":[{\"productKey\":\"pk-sub\",\"deviceName\":\"sub-1\",\"deviceSecret\":\"secret\"}]}"));

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().get("registered"));
        assertEquals(1, result.getData().get("total"));
    }

    private MqttDownlinkService service() {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDevice(anyString())).thenReturn(null);
        when(configManager.getAllDevices()).thenReturn(List.<DeviceInfo>of());
        return new MqttDownlinkService(
                new ObjectMapper(),
                configManager,
                mock(ConfigSyncService.class),
                new ReportProperties(),
                mock(CollectionManager.class),
                mock(ShadowManager.class));
    }

    private byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
