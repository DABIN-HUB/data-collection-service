package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.opc.OpcDaCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * OPC DA 协议元数据提供者。
 */
@Component
@Order(100)
public class OpcDaProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("OPC_DA", "OPC DA",
                "OPC DA access through local or bridge mode.",
                List.of(), OpcDaCollector.class, "OPC_DA", null,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                List.of("Channel1.Device1.Tag1", "Random.Real8"),
                registry.fields(
                        registry.field("host", "string", "Host", true, "127.0.0.1", null, "connection"),
                        registry.field("serverProgId", "string", "Server ProgID", true, "Matrikon.OPC.Simulation.1", null, "connection"),
                        registry.field("progId", "string", "ProgID alias", false, "Matrikon.OPC.Simulation.1", null, "connection"),
                        registry.field("clsid", "string", "CLSID alias", false, "", null, "connection"),
                        registry.field("bridgeMode", "select", "Bridge mode", true, "INMEMORY",
                                List.of("INMEMORY", "HTTP"), "bridge"),
                        registry.conditional("bridgeBaseUrl", "string", "Bridge base URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge", "bridgeMode=HTTP"),
                        registry.field("url", "string", "Bridge or access URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge"),
                        registry.field("bridgeToken", "password", "Bridge token", false, "", null, "bridge"),
                        registry.field("bridgeRetryCount", "number", "Bridge retry count", false, "1", null, "advanced"),
                        registry.field("bridgeRetryBackoffMs", "number", "Bridge retry backoff (ms)", false, "200", null, "advanced"),
                        registry.field("username", "string", "Username", false, "", null, "security"),
                        registry.field("password", "password", "Password", false, "", null, "security"),
                        registry.field("domain", "string", "Windows domain", false, "", null, "security"),
                        registry.field("requestTimeout", "number", "Request timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("updateRate", "number", "Subscription refresh interval (ms)", false, "1000", null, "advanced")))
                .withPointFields(pointFields(registry)));
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.itemId", "string", "Item ID", false, "",
                        Collections.emptyList(), "OPC DA item identifier. When empty, address is used directly.", null),
                registry.pointField("additionalConfig.itemPath", "string", "Item path", false, "",
                        Collections.emptyList(), "Optional OPC DA item path.", null),
                registry.pointField("additionalConfig.dataSource", "select", "Data source", false, "DEVICE",
                        List.of("DEVICE", "CACHE"), "Whether reads should use device data or the OPC cache.", null)
        );
    }
}
