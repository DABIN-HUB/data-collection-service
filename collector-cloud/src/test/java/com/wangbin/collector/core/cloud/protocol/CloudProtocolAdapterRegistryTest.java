package com.wangbin.collector.core.cloud.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkCloudProtocolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudProtocolAdapterRegistryTest {

    @Test
    void shouldResolveDefaultAndAliases() {
        CloudProtocolAdapter adapter = AlinkCloudProtocolAdapter.standalone(new ObjectMapper());
        CloudProtocolAdapterRegistry registry = new CloudProtocolAdapterRegistry(List.of(adapter));

        assertSame(adapter, registry.resolve(null));
        assertSame(adapter, registry.resolve("alink"));
        assertSame(adapter, registry.resolve("aliyun"));
        assertSame(adapter, registry.resolve("ali"));
    }

    @Test
    void shouldRejectUnsupportedProvider() {
        CloudProtocolAdapter adapter = AlinkCloudProtocolAdapter.standalone(new ObjectMapper());
        CloudProtocolAdapterRegistry registry = new CloudProtocolAdapterRegistry(List.of(adapter));

        assertThrows(IllegalArgumentException.class, () -> registry.resolve("huawei"));
    }
}
