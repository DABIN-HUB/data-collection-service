package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.knx.KnxNetIpCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * KNXnet/IP 协议元数据提供者。
 */
@Component
@Order(90)
public class KnxProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("KNXNET_IP", "KNXnet/IP",
                "PLC4X-backed KNXnet/IP group address collector.",
                List.of("KNX", "KNXNETIP", "KNXNET/IP", "KNX_NET_IP"), KnxNetIpCollector.class, "KNXNET_IP", 3671,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("1/2/3:DPT1.001", "1/200:DPT9.001", "12345:DPT5.001"),
                registry.fields(
                        registry.field("host", "string", "Device host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "3671", null, "connection"),
                        registry.field("groupAddressNumLevels", "number", "Group address levels", false, "3", null, "protocol"),
                        registry.field("knxConnectionType", "select", "KNX connection type", false, "LINK_LAYER",
                                List.of("LINK_LAYER", "RAW", "BUSMONITOR"), "protocol"),
                        registry.field("requestTimeout", "number", "PLC4X request timeout (ms)", false, "10000", null, "advanced"),
                        registry.field("maxFieldsPerRequest", "number", "Max fields per request", false, "30", null, "advanced"),
                        registry.field("knxprojFilePath", "string", "KNX project file path", false, "", null, "advanced"),
                        registry.field("knxprojPassword", "password", "KNX project password", false, "", null, "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "10000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "10000", null, "advanced")))
                .withProtocolFieldPrimarySchema("additionalConfig.dptId", pointFields(registry)));
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.dptId", "string", "DPT id", false, "",
                        Collections.emptyList(), "KNX datapoint type identifier. More precise than the platform dataType for wire-level decoding.", null),
                registry.pointField("additionalConfig.dpt", "string", "DPT alias", false, "",
                        Collections.emptyList(), "Compatibility alias for older KNX DPT configurations.", null)
        );
    }
}
