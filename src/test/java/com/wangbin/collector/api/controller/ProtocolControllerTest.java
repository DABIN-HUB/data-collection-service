package com.wangbin.collector.api.controller;

import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import com.wangbin.collector.core.config.protocol.ProtocolFieldConfig;
import com.wangbin.collector.core.config.protocol.ProtocolSchema;
import com.wangbin.collector.core.config.protocol.ProtocolSchemaService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolControllerTest {

    private final ProtocolDescriptorRegistry registry = ProtocolDescriptorTestProviders.registry();
    private final ProtocolController controller = new ProtocolController(new ProtocolSchemaService(registry));

    @Test
    void shouldListProtocols() {
        ApiResult<List<ProtocolSchema>> result = controller.listProtocols();

        assertEquals(200, result.getCode());
        assertEquals(registry.primaryDescriptors().size(), result.getData().size());
        assertTrue(result.getData().stream().anyMatch(schema -> "BACNET_IP".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "BACNET_MSTP".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "BACNET_SC".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "SIEMENS_S7".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "MITSUBISHI_MC".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "OMRON_FINS".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "ETHERNET_IP".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "ADS".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "KNXNET_IP".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "OPC_UA_PLC4X".equals(schema.getProtocol())));
    }

    @Test
    void shouldReturnFieldsForAlias() {
        ApiResult<List<ProtocolFieldConfig>> result = controller.getConnectionFields("MQTT_SSL");

        assertEquals(200, result.getCode());
        assertTrue(result.getData().size() >= 10);
        assertTrue(result.getData().stream().anyMatch(field -> "clientId".equals(field.getName())));
    }

    @Test
    void shouldReturnFieldsForBacnetAlias() {
        ApiResult<List<ProtocolFieldConfig>> result = controller.getConnectionFields("BACNET/IP");

        assertEquals(200, result.getCode());
        assertTrue(result.getData().stream().anyMatch(field -> "remoteDeviceInstance".equals(field.getName())));
        assertTrue(result.getData().stream().anyMatch(field -> "covEnabled".equals(field.getName())));
    }

    @Test
    void shouldReturnErrorForUnsupportedProtocol() {
        ApiResult<ProtocolSchema> result = controller.getProtocol("UNKNOWN");

        assertEquals(1001, result.getCode());
    }
}
