package com.wangbin.collector.core.collector.factory;

import com.wangbin.collector.common.domain.enums.ProtocolType;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorFactoryProtocolMappingTest {

    @Test
    void shouldSupportAllProtocolTypeEnumCodes() {
        CollectorFactory factory = new CollectorFactory(null, ProtocolDescriptorTestProviders.registry());

        for (ProtocolType protocolType : ProtocolType.values()) {
            assertTrue(
                    factory.supportsProtocol(protocolType.getCode()),
                    "Unsupported protocol code in CollectorFactory: " + protocolType.getCode()
            );
        }
    }
}
