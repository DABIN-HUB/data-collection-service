package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.iec.Iec61850Collector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * IEC 61850 协议元数据提供者。
 */
@Component
@Order(170)
public class Iec61850ProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("IEC61850", "IEC 61850",
                "IEC61850 MMS collection.",
                List.of("IEC_61850"), Iec61850Collector.class, "IEC61850", 102,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                List.of("LD0/MMXU1.A.phsA.cVal.mag.f"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "MMS port", true, "102", null, "connection"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", true, "10000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "10000", null, "advanced"))));
    }
}
