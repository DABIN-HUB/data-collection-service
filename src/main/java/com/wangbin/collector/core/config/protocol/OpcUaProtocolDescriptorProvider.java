package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.opc.OpcUaCollector;
import com.wangbin.collector.core.collector.protocol.opc.Plc4xOpcUaCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * OPC UA 协议元数据提供者。
 */
@Component
@Order(30)
public class OpcUaProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    /**
     * 注册 OPC UA 的 PLC4X 和 Milo 两类驱动元数据。
     *
     * @param registry 协议元数据注册表
     */
    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("OPC_UA", "OPC UA",
                "基于 PLC4X 的 OPC UA 统一架构采集器。",
                List.of("OPCUA"), Plc4xOpcUaCollector.class, "OPC_UA", 4840, ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001", "ns=3;i=1001;REAL"),
                opcUaFields(registry))
                .withDriverPrimarySchema("OPC UA 驱动数据类型", opcUaDriverDataTypes(), opcUaPointFields(registry)));
        registry.registerPrimary(registry.descriptor("OPC_UA_PLC4X", "OPC UA（PLC4X 别名）",
                "保留的 PLC4X OPC UA 采集器历史别名，用于兼容旧配置。",
                List.of("OPCUA_PLC4X"), Plc4xOpcUaCollector.class, "OPC_UA_PLC4X", 4840, ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001;REAL"),
                opcUaFields(registry))
                .withDriverPrimarySchema("OPC UA 驱动数据类型", opcUaDriverDataTypes(), opcUaPointFields(registry)));
        registry.registerPrimary(registry.descriptor("OPC_UA_MILO", "OPC UA（Milo 实验驱动）",
                "使用 Eclipse Milo 的独立 OPC UA 客户端，需要完成实服契约测试后再用于生产。",
                List.of("OPCUA_MILO"), OpcUaCollector.class, "OPC_UA_MILO", 4840, ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001"),
                opcUaMiloFields(registry))
                .withDriverPrimarySchema("OPC UA 驱动数据类型", opcUaDriverDataTypes(), opcUaPointFields(registry)));
    }

    /**
     * 构建 PLC4X OPC UA 连接字段。
     *
     * @param registry 协议元数据注册表
     * @return 连接字段列表
     */
    private List<ProtocolFieldConfig> opcUaFields(ProtocolDescriptorRegistry registry) {
        return registry.fields(
                registry.field("url", "string", "端点 URL", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                registry.field("endpointUrl", "string", "端点 URL 别名", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                registry.field("endpoint", "string", "端点别名", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                registry.field("host", "string", "主机", false, "127.0.0.1", null, "connection"),
                registry.field("port", "number", "端口", false, "4840", null, "connection"),
                registry.field("discovery", "boolean", "使用发现端点", false, "false",
                        List.of("true", "false"), "protocol"),
                registry.field("authType", "select", "认证类型", false, "ANONYMOUS",
                        List.of("ANONYMOUS", "USERNAME", "CERT"), "security"),
                registry.field("securityPolicy", "select", "安全策略", false, "NONE",
                        List.of("NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256",
                                "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"),
                        "security"),
                registry.field("messageSecurity", "select", "消息安全模式", false, "NONE",
                        List.of("NONE", "SIGN", "SIGN_ENCRYPT"), "security"),
                registry.field("securityMode", "select", "安全模式别名", false, "NONE",
                        List.of("NONE", "Sign", "SignAndEncrypt"), "security"),
                registry.conditional("username", "string", "用户名", false, "", null, "security", "authType=USERNAME"),
                registry.field("password", "password", "密码", false, "", null, "security"),
                registry.field("authParams", "object", "认证参数别名", false, "{}", null, "security"),
                registry.field("keyStoreFile", "string", "客户端密钥库文件", false, "", null, "security"),
                registry.field("keyStoreType", "string", "客户端密钥库类型", false, "pkcs12", null, "security"),
                registry.field("keyStorePassword", "password", "客户端密钥库密码", false, "", null, "security"),
                registry.conditional("clientCertPath", "string", "客户端证书别名", false, "", null,
                        "security", "authType=CERT or securityPolicy!=NONE"),
                registry.field("clientCertPassword", "password", "客户端证书密码别名", false, "", null, "security"),
                registry.field("trustStoreFile", "string", "信任库文件", false, "", null, "security"),
                registry.field("trustStoreType", "string", "信任库类型", false, "pkcs12", null, "security"),
                registry.field("trustStorePassword", "password", "信任库密码", false, "", null, "security"),
                registry.field("serverCertificateFile", "string", "固定服务端证书文件", false, "", null, "security"),
                registry.field("endpointHost", "string", "端点主机覆盖", false, "", null, "advanced"),
                registry.field("endpointPort", "number", "端点端口覆盖", false, "", null, "advanced"),
                registry.field("channelLifetime", "number", "安全通道生命周期（毫秒）", false, "3600000", null, "advanced"),
                registry.field("sessionTimeout", "number", "会话超时（毫秒）", false, "120000", null, "advanced"),
                registry.field("negotiationTimeout", "number", "协商超时（毫秒）", false, "5000", null, "advanced"),
                registry.field("connectTimeoutMs", "number", "连接超时别名（毫秒）", false, "5000", null, "advanced"),
                registry.field("connectTimeout", "number", "连接超时别名（毫秒）", false, "5000", null, "advanced"),
                registry.field("requestTimeout", "number", "请求超时（毫秒）", false, "5000", null, "advanced"),
                registry.field("requestTimeoutMs", "number", "请求超时别名（毫秒）", false, "5000", null, "advanced"),
                registry.field("subscriptionInterval", "number", "订阅间隔（毫秒）", false, "1000", null, "advanced"),
                registry.field("maxFieldsPerRequest", "number", "单次最大字段数", false, "100", null, "advanced"),
                registry.field("plc4xConnectionString", "string", "PLC4X 连接串", false, "", null, "advanced"));
    }

    /**
     * 构建 Milo OPC UA 连接字段。
     *
     * @param registry 协议元数据注册表
     * @return 连接字段列表
     */
    private List<ProtocolFieldConfig> opcUaMiloFields(ProtocolDescriptorRegistry registry) {
        return opcUaFields(registry).stream()
                .filter(field -> !"plc4xConnectionString".equals(field.getName()))
                .toList();
    }

    private List<String> opcUaDriverDataTypes() {
        return List.of(
                "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT",
                "LINT", "ULINT", "REAL", "LREAL", "CHAR", "WCHAR", "STRING",
                "TIME", "DATE", "DATE_AND_TIME");
    }

    private List<ProtocolFieldConfig> opcUaPointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.nodeId", "string", "NodeId", false, "",
                        Collections.emptyList(), "Explicit OPC UA NodeId. When empty, the collector can still use address directly.", null),
                registry.pointField("additionalConfig.namespace", "number", "Namespace", false, "",
                        Collections.emptyList(), "Used together with identifier to build a NodeId.", null),
                registry.pointField("additionalConfig.identifier", "string", "Identifier", false, "",
                        Collections.emptyList(), "Node identifier used together with namespace.", null),
                registry.pointField("additionalConfig.identifierType", "select", "Identifier type", false, "STRING",
                        List.of("STRING", "NUMERIC", "GUID", "OPAQUE"),
                        "Encoding type for the OPC UA NodeId identifier.", null),
                registry.pointField("additionalConfig.samplingInterval", "number", "Sampling interval (ms)", false, "",
                        Collections.emptyList(), "Sampling interval used for subscriptions or monitored items.", null),
                registry.pointField("additionalConfig.publishingInterval", "number", "Publishing interval (ms)", false, "",
                        Collections.emptyList(), "Publishing interval used for subscriptions.", null),
                registry.pointField("additionalConfig.queueSize", "number", "Queue size", false, "",
                        Collections.emptyList(), "Subscription queue size for buffered notifications.", null),
                registry.pointField("additionalConfig.arraySize", "number", "数组长度", false, "",
                        Collections.emptyList(), "OPC UA 一维数组节点的元素数量，标量点位保持为空。", null),
                registry.pointField("additionalConfig.subscribe", "boolean", "Subscribe", false, "",
                        List.of("true", "false"), "Whether this point should use subscription mode.", null),
                registry.pointField("additionalConfig.monitor", "boolean", "Monitor alias", false, "",
                        List.of("true", "false"), "Compatibility alias for older monitored-item configurations.", null)
        );
    }
}
