package com.wangbin.collector.common.domain.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudTargetConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepJsonContractAfterMovingToCommon() throws Exception {
        CloudTargetConfig config = new CloudTargetConfig();
        config.setEnabled(true);
        config.setDeviceType(CloudDeviceType.GATEWAY);
        config.setProductKey("pk-1");
        config.setDeviceName("device-1");
        config.setTopologyEnabled(false);

        String json = objectMapper.writeValueAsString(config);
        CloudTargetConfig restored = objectMapper.readValue(json, CloudTargetConfig.class);

        assertTrue(json.contains("\"enabled\""));
        assertTrue(json.contains("\"deviceType\""));
        assertTrue(json.contains("\"productKey\""));
        assertTrue(json.contains("\"deviceName\""));
        assertTrue(json.contains("\"topologyEnabled\""));
        assertEquals("pk-1", restored.getProductKey());
        assertEquals("device-1", restored.getDeviceName());
        assertEquals(CloudDeviceType.GATEWAY, restored.getDeviceType());
        assertEquals(CloudDeviceIdentity.of("pk-1", "device-1"), restored.identity());
        assertTrue(restored.gatewayDevice());
    }
}
