package com.wangbin.collector.core.cloud.mapping;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cloud.aggregation.CloudPointBinding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudPointMappingResolverTest {

    @Test
    void shouldResolveCloudBindingsAndNormalizeMessageType() {
        DataPoint point = new DataPoint();
        point.setPointCode("temp_raw");
        point.setAdditionalConfig(Map.of(
                "cloudBindings",
                List.of(Map.of(
                        "productKey", "pk-a",
                        "deviceName", "device-a",
                        "field", "temperature",
                        "messageType", "event"))));

        List<CloudPointBinding> bindings = new CloudPointMappingResolver()
                .resolve(point, "default-pk", "default-device");

        assertEquals(1, bindings.size());
        CloudPointBinding binding = bindings.get(0);
        assertEquals("pk-a", binding.identity().productKey());
        assertEquals("device-a", binding.identity().deviceName());
        assertEquals("temperature", binding.field());
        assertEquals(MessageConstant.MESSAGE_TYPE_EVENT_POST, binding.messageType());
    }

    @Test
    void shouldFallbackToDefaultCloudDeviceWhenNoBindingsConfigured() {
        DataPoint point = new DataPoint();
        point.setPointCode("humidity_raw");
        point.setAdditionalConfig(new LinkedHashMap<>(Map.of("reportField", "humidity")));

        List<CloudPointBinding> bindings = new CloudPointMappingResolver()
                .resolve(point, "pk-default", "device-default");

        assertEquals(1, bindings.size());
        assertEquals("pk-default", bindings.get(0).identity().productKey());
        assertEquals("device-default", bindings.get(0).identity().deviceName());
        assertEquals("humidity", bindings.get(0).field());
        assertEquals(MessageConstant.MESSAGE_TYPE_PROPERTY_POST, bindings.get(0).messageType());
    }
}
