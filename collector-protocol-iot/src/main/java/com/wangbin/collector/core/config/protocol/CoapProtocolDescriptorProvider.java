package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.coap.CoapCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * CoAP 协议元数据提供者。
 */
@Component
@Order(120)
public class CoapProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("COAP", "CoAP",
                "CoAP request/response collection protocol.",
                List.of("COAP_SSL"), CoapCollector.class, "COAP", 5683,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("/sensors/temp", "coap://device.local/sensors/humidity"),
                registry.fields(
                        registry.field("url", "string", "CoAP base URL", false, "coap://127.0.0.1:5683", null, "connection"),
                        registry.field("host", "string", "Device host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "5683", null, "connection"),
                        registry.field("scheme", "select", "Scheme", false, "coap", List.of("coap", "coaps"), "connection"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced"),
                        registry.field("maxPendingMessages", "number", "Max pending requests", false, "1024", null, "advanced"),
                        registry.field("dispatchBatchSize", "number", "Dispatch batch size", false, "1", null, "advanced"),
                        registry.field("dispatchFlushInterval", "number", "Dispatch flush interval (ms)", false, "0", null, "advanced"),
                        registry.field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced")))
                .withPointFields(pointFields(registry)));

        registry.registerAlias("COAP_SSL", "COAP", cfg -> {
            cfg.setSslEnabled(true);
            ProtocolDescriptorRegistry.applyDefaultPort(cfg, 5684);
            ProtocolDescriptorRegistry.putExtIfAbsent(cfg, "scheme", "coaps");
        });
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.path", "string", "Path", false, "",
                        Collections.emptyList(), "CoAP resource path when address is not a full URI.", null),
                registry.pointField("additionalConfig.method", "select", "Method", false, "GET",
                        List.of("GET", "POST", "PUT", "DELETE"), "HTTP-like CoAP method used for the point.", null),
                registry.pointField("additionalConfig.query", "string", "Query", false, "",
                        Collections.emptyList(), "Query string appended to the CoAP resource path.", null),
                registry.pointField("additionalConfig.mediaType", "select", "Media type", false, "TEXT",
                        List.of("TEXT", "JSON", "CBOR", "OCTET"), "Payload media-type hint used for request/response decoding.", null),
                registry.pointField("additionalConfig.observe", "boolean", "Observe", false, "",
                        List.of("true", "false"), "Whether this point should use CoAP Observe subscription mode.", null),
                registry.pointField("additionalConfig.binary", "boolean", "Binary payload", false, "",
                        List.of("true", "false"), "Whether the payload should be treated as binary data.", null)
        );
    }
}
