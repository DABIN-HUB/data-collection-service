package com.wangbin.collector.core.report.downlink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttDownlinkServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void propertySetWritesDesiredAndRoutesToCollectionManager() {
        ConfigManager configManager = mock(ConfigManager.class);
        CollectionManager collectionManager = mock(CollectionManager.class);
        ShadowManager shadowManager = new ShadowManager(new ReportProperties());
        MqttDownlinkService service = new MqttDownlinkService(
                new ObjectMapper(), configManager, collectionManager, shadowManager);

        DeviceInfo device = device("dev-1", "fjb_001");
        DataPoint point = point("p1", "sqs", "RW");
        when(configManager.getDevice("dev-1")).thenReturn(device);
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));
        when(collectionManager.writePoints(eq("dev-1"), anyMap())).thenReturn(Map.of("p1", true));

        String payload = """
                {"id":"m1","version":"1.0","method":"thing.property.set","deviceId":"dev-1","params":{"sqs":25}}
                """;
        MqttDownlinkResult result = service.handle(
                "/sys/pk/fjb_001/thing/property/set",
                payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, result.getCode());
        verify(collectionManager).writePoints(eq("dev-1"), anyMap());

        Map<String, Object> shadow = shadowManager.getShadowDocument("dev-1");
        Map<String, Object> state = (Map<String, Object>) shadow.get("state");
        assertEquals(Map.of("sqs", 25), state.get("desired"));
        assertEquals(Map.of("sqs", 25), state.get("delta"));
    }

    @Test
    void serviceInvokeRoutesToCollectionManager() {
        ConfigManager configManager = mock(ConfigManager.class);
        CollectionManager collectionManager = mock(CollectionManager.class);
        ShadowManager shadowManager = new ShadowManager(new ReportProperties());
        MqttDownlinkService service = new MqttDownlinkService(
                new ObjectMapper(), configManager, collectionManager, shadowManager);

        when(configManager.getDevice("dev-1")).thenReturn(device("dev-1", "fjb_001"));
        when(collectionManager.executeCommand(eq("dev-1"), eq("restart"), anyMap()))
                .thenReturn(Map.of("accepted", true));

        String payload = """
                {"id":"m2","method":"thing.service.invoke","deviceId":"dev-1","params":{"command":"restart"}}
                """;
        MqttDownlinkResult result = service.handle(
                "/sys/pk/fjb_001/thing/service/invoke",
                payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, result.getCode());
        assertEquals(MessageConstant.MESSAGE_TYPE_SERVICE_INVOKE, result.getMethod());
        assertTrue(((Map<?, ?>) result.getData().get("result")).containsKey("accepted"));
    }

    @Test
    void configPushIsRecognizedButNotExecuted() {
        ConfigManager configManager = mock(ConfigManager.class);
        CollectionManager collectionManager = mock(CollectionManager.class);
        ShadowManager shadowManager = new ShadowManager(new ReportProperties());
        MqttDownlinkService service = new MqttDownlinkService(
                new ObjectMapper(), configManager, collectionManager, shadowManager);

        when(configManager.getDevice("dev-1")).thenReturn(device("dev-1", "fjb_001"));
        String payload = """
                {"id":"m3","method":"thing.config.push","deviceId":"dev-1","params":{"x":1}}
                """;

        MqttDownlinkResult result = service.handle(
                "/sys/pk/fjb_001/thing/config/push",
                payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(501, result.getCode());
    }

    private DeviceInfo device(String deviceId, String deviceName) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceName);
        return device;
    }

    private DataPoint point(String pointId, String pointCode, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setReadWrite(readWrite);
        point.setStatus(1);
        return point;
    }
}
