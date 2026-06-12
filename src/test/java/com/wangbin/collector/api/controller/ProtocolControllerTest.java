package com.wangbin.collector.api.controller;

import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.config.protocol.ProtocolFieldConfig;
import com.wangbin.collector.core.config.protocol.ProtocolSchema;
import com.wangbin.collector.core.config.protocol.ProtocolSchemaService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolControllerTest {

    private final ProtocolController controller = new ProtocolController(new ProtocolSchemaService());

    @Test
    void shouldListProtocols() {
        ApiResult<List<ProtocolSchema>> result = controller.listProtocols();

        assertEquals(200, result.getCode());
        assertEquals(14, result.getData().size());
        assertTrue(result.getData().stream().anyMatch(schema -> "SIEMENS_S7".equals(schema.getProtocol())));
        assertTrue(result.getData().stream().anyMatch(schema -> "ETHERNET_IP".equals(schema.getProtocol())));
    }

    @Test
    void shouldReturnFieldsForAlias() {
        ApiResult<List<ProtocolFieldConfig>> result = controller.getConnectionFields("MQTT_SSL");

        assertEquals(200, result.getCode());
        assertTrue(result.getData().size() >= 10);
        assertTrue(result.getData().stream().anyMatch(field -> "clientId".equals(field.getName())));
    }

    @Test
    void shouldReturnErrorForUnsupportedProtocol() {
        ApiResult<ProtocolSchema> result = controller.getProtocol("UNKNOWN");

        assertEquals(1001, result.getCode());
    }
}
