package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetMstpCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetScCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * BACnet 协议族元数据提供者。
 */
@Component
@Order(40)
public class BacnetProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("BACNET_IP", "BACnet/IP",
                "BACnet/IP building automation protocol collector.",
                List.of("BACNET", "BACNETIP", "BACNET/IP"), BacnetIpCollector.class, "BACNET_IP", 47808,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("analogInput:1.presentValue", "binaryOutput:3.presentValue", "device:1001.objectName"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "BACnet/IP target device host or IP address."),
                        registry.field("port", "number", "Port", false, "47808", null, "connection",
                                "BACnet/IP UDP port. Leave empty to use the default 47808."),
                        registry.field("localBindHost", "string", "Local bind host", false, "0.0.0.0", null, "connection",
                                "Local UDP bind host used by the BACnet/IP client."),
                        registry.field("localBindPort", "number", "Local bind port", false, "", null, "connection",
                                "Local UDP bind port. Required when enabling COV subscription paths."),
                        registry.field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol",
                                "Target BACnet device instance number."),
                        registry.field("localDeviceInstance", "number", "Local device instance", false, "4194302", null, "protocol",
                                "Local BACnet client device instance used by the collector."),
                        registry.field("useWhoIsDiscovery", "boolean", "Use Who-Is discovery", false, "false",
                                List.of("true", "false"), "protocol",
                                "Whether the adapter should issue Who-Is / I-Am discovery before normal polling."),
                        registry.field("networkNumber", "number", "Network number", false, "", null, "protocol",
                                "Optional routed BACnet network number."),
                        registry.field("macAddress", "string", "MAC address", false, "", null, "protocol",
                                "Optional routed-device MAC address hint."),
                        registry.field("covEnabled", "boolean", "Enable COV subscription", false, "false",
                                List.of("true", "false"), "subscription",
                                "Whether COV subscription paths are enabled for this connection."),
                        registry.field("defaultCovLifetimeSeconds", "number", "Default COV lifetime (s)", false, "300", null, "subscription",
                                "Default subscription lifetime used for COV renewals."),
                        registry.field("defaultCovIncrement", "number", "Default COV increment", false, "", null, "subscription",
                                "Default COV increment threshold for analog objects."),
                        registry.field("resubscribeOnReconnect", "boolean", "Resubscribe on reconnect", false, "true",
                                List.of("true", "false"), "subscription",
                                "Whether active subscriptions should be restored after reconnect."),
                        registry.field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        registry.field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        registry.field("maxPropertiesPerRequest", "number", "Max properties per request", false, "32", null, "advanced"),
                        registry.field("readPropertyMultipleEnabled", "boolean", "Enable ReadPropertyMultiple", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("writePropertyMultipleEnabled", "boolean", "Enable WritePropertyMultiple", false, "false",
                                List.of("true", "false"), "advanced"),
                        registry.field("bbmdHost", "string", "BBMD host", false, "", null, "advanced"),
                        registry.field("bbmdPort", "number", "BBMD port", false, "", null, "advanced"),
                        registry.field("foreignDeviceTtlSeconds", "number", "Foreign device TTL (s)", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")))
                .withDriverPrimarySchema("BACnet driver type", bacnetDriverDataTypes(), bacnetPointFields(registry)));

        registry.registerPrimary(registry.descriptor("BACNET_MSTP", "BACnet MS/TP",
                "BACnet MS/TP collector over RS485 with token passing, CRC framing and serial transport.",
                List.of("BACNETMSTP", "BACNET-MS/TP", "BACNET_MSTP"), BacnetMstpCollector.class, "BACNET_MSTP",
                null, ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("analogInput:1.presentValue", "device:1001.objectName", "128:42.512"),
                registry.fields(
                        registry.field("serialPort", "string", "Serial port", true, "COM1", null, "connection",
                                "RS485 serial port used by BACnet MS/TP transport."),
                        registry.field("baudRate", "number", "Baud rate", false, "38400", null, "connection",
                                "MS/TP serial baud rate. Common values are 9600, 19200, 38400 or 76800."),
                        registry.field("dataBits", "number", "Data bits", false, "8", null, "connection"),
                        registry.field("stopBits", "number", "Stop bits", false, "1", null, "connection"),
                        registry.field("parity", "select", "Parity", false, "none", List.of("none", "odd", "even"), "connection"),
                        registry.field("localMacAddress", "number", "Local MAC address", true, "1", null, "protocol",
                                "Local BACnet MS/TP master MAC address on the token ring."),
                        registry.field("remoteMacAddress", "number", "Remote MAC address", true, "2", null, "protocol",
                                "Target BACnet MS/TP remote MAC address used for confirmed requests."),
                        registry.field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol",
                                "Target BACnet device instance number."),
                        registry.field("remoteIsMaster", "boolean", "Remote is master", false, "true", List.of("true", "false"), "protocol"),
                        registry.field("maxMaster", "number", "Max master", false, "127", null, "advanced"),
                        registry.field("maxInfoFrames", "number", "Max info frames", false, "1", null, "advanced"),
                        registry.field("nextStationMac", "number", "Next station MAC", false, "", null, "advanced"),
                        registry.field("tokenClaimTimeoutMs", "number", "Token claim timeout (ms)", false, "1000", null, "advanced"),
                        registry.field("pollForMasterTimeoutMs", "number", "Poll-for-master timeout (ms)", false, "250", null, "advanced"),
                        registry.field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        registry.field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")))
                .withDriverPrimarySchema("BACnet MS/TP driver type", bacnetDriverDataTypes(), bacnetPointFields(registry)));

        registry.registerPrimary(registry.descriptor("BACNET_SC", "BACnet/SC",
                "Experimental BACnet/SC collector over secure WebSocket transport.",
                List.of("BACNETSC", "BACNET/SC", "BACNET-SC"), BacnetScCollector.class, "BACNET_SC", 443,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("analogInput:1.presentValue", "device:1001.objectName", "128:42.512"),
                registry.fields(
                        registry.field("url", "string", "Secure WebSocket URL", false, "wss://127.0.0.1:443/bacnet/sc", null, "connection"),
                        registry.field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "443", null, "connection"),
                        registry.field("path", "string", "Path", false, "/bacnet/sc", null, "connection"),
                        registry.field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol"),
                        registry.field("subprotocol", "string", "WebSocket subprotocol", false, "bacnet-sc", null, "protocol"),
                        registry.field("keyStoreFile", "string", "客户端密钥库文件", true, "", null, "security"),
                        registry.field("keyStoreType", "string", "客户端密钥库类型", false, "PKCS12", null, "security"),
                        registry.field("keyStorePassword", "password", "客户端密钥库密码", true, "", null, "security"),
                        registry.field("trustStoreFile", "string", "服务端信任库文件", true, "", null, "security"),
                        registry.field("trustStoreType", "string", "服务端信任库类型", false, "PKCS12", null, "security"),
                        registry.field("trustStorePassword", "password", "服务端信任库密码", true, "", null, "security"),
                        registry.field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        registry.field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("connectTimeout", "number", "Connect timeout (ms)", false, "5000", null, "advanced")))
                .withDriverPrimarySchema("BACnet/SC driver type", bacnetDriverDataTypes(), bacnetPointFields(registry)));
    }

    private List<String> bacnetDriverDataTypes() {
        return List.of("AUTO", "BOOLEAN", "UNSIGNED", "SIGNED", "REAL", "DOUBLE", "ENUM", "STRING", "BIT_STRING");
    }

    private List<ProtocolFieldConfig> bacnetPointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.driverDataType", "select", "BACnet driver type", false, "AUTO",
                        bacnetDriverDataTypes(),
                        "BACnet native value-type hint used for decoding enums, bit strings, numeric values, and strings.", null),
                registry.pointField("additionalConfig.arrayIndex", "number", "Array index", false, "",
                        Collections.emptyList(), "Optional BACnet property array index when the address does not already include [n].", null),
                registry.pointField("additionalConfig.writePriority", "number", "Write priority", false, "",
                        Collections.emptyList(), "Optional BACnet write priority for commandable presentValue writes.", null),
                registry.pointField("additionalConfig.covMode", "select", "COV mode", false, "",
                        List.of("OBJECT", "PROPERTY"),
                        "Optional subscription mode used by SubscribeCOV or SubscribeCOVProperty flows.", null),
                registry.pointField("additionalConfig.covIncrement", "number", "COV increment", false, "",
                        Collections.emptyList(), "Optional per-point COV increment override for analog objects.", null)
        );
    }
}
