package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.ads.AdsCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetMstpCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetScCollector;
import com.wangbin.collector.core.collector.protocol.coap.CoapCollector;
import com.wangbin.collector.core.collector.protocol.custom.CustomProtocolCollector;
import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645Collector;
import com.wangbin.collector.core.collector.protocol.ethernetip.EtherNetIpCollector;
import com.wangbin.collector.core.collector.protocol.http.HttpCollector;
import com.wangbin.collector.core.collector.protocol.iec.Iec104Collector;
import com.wangbin.collector.core.collector.protocol.iec.Iec61850Collector;
import com.wangbin.collector.core.collector.protocol.iec101.Iec101Collector;
import com.wangbin.collector.core.collector.protocol.knx.KnxNetIpCollector;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusRtuCollector;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusTcpCollector;
import com.wangbin.collector.core.collector.protocol.mc.McCollector;
import com.wangbin.collector.core.collector.protocol.fins.OmronFinsCollector;
import com.wangbin.collector.core.collector.protocol.mqtt.MqttCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcDaCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcUaCollector;
import com.wangbin.collector.core.collector.protocol.opc.Plc4xOpcUaCollector;
import com.wangbin.collector.core.collector.protocol.s7.S7Collector;
import com.wangbin.collector.core.collector.protocol.snmp.SnmpCollector;
import com.wangbin.collector.core.collector.protocol.websocket.WebSocketCollector;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class ProtocolDescriptorRegistry {

    public static final List<String> COMMON_DATA_TYPES = List.of(
            "INT", "FLOAT", "DOUBLE", "BOOLEAN", "STRING", "BYTE", "SHORT", "LONG", "UINT16", "UINT32");
    public static final List<String> EXTENDED_DATA_TYPES = appendOptions(COMMON_DATA_TYPES,
            "INT8", "UINT8", "INT16", "INT32", "FLOAT32", "FLOAT64", "INT64", "UINT64");
    public static final List<String> MODBUS_DATA_TYPES = appendOptions(EXTENDED_DATA_TYPES,
            "FLOAT32_SWAP", "FLOAT32_LITTLE", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE_SWAP");
    private static final Set<String> TOP_LEVEL_CONNECTION_FIELDS = new HashSet<>(List.of(
            "connectionType", "host", "port", "url", "connectTimeout", "readTimeout", "writeTimeout",
            "timeout", "heartbeatInterval", "heartbeatTimeout", "subscriptionInterval", "reconnectDelay",
            "username", "password", "clientId", "productKey", "deviceSecret", "authToken",
            "sslEnabled", "sslCertPath", "sslKeyPath", "keepAlive", "bufferSize",
            "autoReconnect", "maxPendingMessages", "dispatchBatchSize", "dispatchFlushInterval",
            "overflowStrategy", "securityPolicy", "authParams"
    ));

    private final Map<String, ProtocolDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, AliasDescriptor> aliases = new LinkedHashMap<>();

    public ProtocolDescriptorRegistry() {
        registerPrimary(descriptor("MODBUS_TCP", "Modbus TCP", "Modbus TCP register polling over Ethernet.",
                List.of(), Plc4xModbusTcpCollector.class, "MODBUS_TCP", 502, ProtocolAddressingMode.NUMERIC,
                true, true, false,
                List.of("40001", "HOLDING_REGISTER:1", "COIL:0"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "502", null, "connection"),
                        field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("parity", "select", "Parity", false, "none",
                                List.of("none", "odd", "even"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("pingAddress", "string", "PLC4X ping address", false, "", null, "advanced"),
                        field("maxRegistersPerRequest", "number", "Max registers per request", false, "125", null, "advanced"),
                        field("maxCoilsPerRequest", "number", "Max coils per request", false, "2000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced"))));
        registerPrimary(descriptor("MODBUS_RTU", "Modbus RTU", "Modbus serial line collection.",
                List.of("MODBUS_ASCII"), Plc4xModbusRtuCollector.class, "MODBUS_RTU", null, ProtocolAddressingMode.NUMERIC,
                true, true, false,
                List.of("40001", "INPUT_REGISTER:0", "COIL:10"),
                fields(
                        field("serialPort", "string", "Serial port", true, "COM1", null, "connection"),
                        field("baudRate", "number", "Baud rate", true, "9600", null, "connection"),
                        field("dataBits", "number", "Data bits", true, "8", null, "connection"),
                        field("stopBits", "number", "Stop bits", true, "1", null, "connection"),
                        field("parity", "select", "Parity", true, "none",
                                List.of("none", "odd", "even"), "connection"),
                        field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("interFrameDelay", "number", "Inter-frame delay (ms)", true, "5", null, "advanced"),
                        field("plc4xProtocolCode", "select", "PLC4X protocol code", false, "modbus-rtu",
                                List.of("modbus-rtu", "modbus-ascii"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("maxRegistersPerRequest", "number", "Max registers per request", false, "125", null, "advanced"),
                        field("maxCoilsPerRequest", "number", "Max coils per request", false, "2000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced"))));
        registerPrimary(descriptor("SIEMENS_S7", "Siemens S7", "PLC4X-backed Siemens S7 read/write collector.",
                List.of("S7"), S7Collector.class, "SIEMENS_S7", 102, ProtocolAddressingMode.MIXED,
                true, true, true,
                List.of("DB1.DBX0.0", "DB1.DBW0", "DB1.DBD4", "%DB1:4:REAL", "%DB1.DBX0.0:BOOL", "I0.0", "Q0.0", "M10.0"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "PLC IP address. Used together with port/rack/slot/controllerType when plc4xConnectionString is empty."),
                        field("port", "number", "Port", false, "102", null, "connection",
                                "S7 TCP port. Leave empty to use the default 102."),
                        field("rack", "number", "Rack", false, "0", null, "protocol",
                                "Remote rack used to build the generated PLC4X S7 connection string."),
                        field("slot", "number", "Slot", false, "1", null, "protocol",
                                "Remote slot used to build the generated PLC4X S7 connection string."),
                        field("controllerType", "select", "Controller type", false, "S7_1200",
                                List.of("S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"), "protocol",
                                "Pick the real PLC family. For S7-1200/S7-1500 this collector reads absolute addresses only, so DB optimized block access must be disabled on the PLC side."),
                        field("pduSize", "number", "PDU size", false, "1024", null, "advanced",
                                "Optional PLC4X S7 tuning for negotiated PDU size. Leave empty unless you need compatibility tuning."),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced",
                                "Collector-side batch cap for one PLC4X read/write request. Reduce it when a PLC or gateway rejects large mixed batches."),
                        field("subscriptionEnabled", "select", "Enable subscription", false, "",
                                List.of("true", "false"), "advanced",
                                "Current S7 collector supports cyclic subscription as well as MODE/SYS/USR/ALM event subscriptions when points are configured as subscription/event mode."),
                        field("localTsap", "number", "Local TSAP", false, "", null, "advanced",
                                "Optional PLC4X TSAP override for installations that require explicit PG/OP routing parameters."),
                        field("remoteTsap", "number", "Remote TSAP", false, "", null, "advanced",
                                "Optional PLC4X TSAP override for installations that require explicit PG/OP routing parameters."),
                        field("localDeviceGroup", "select", "Local device group", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced",
                                "Optional PLC4X local device group override used with TSAP-based routing."),
                        field("remoteDeviceGroup", "select", "Remote device group", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced",
                                "Optional PLC4X remote device group override used with TSAP-based routing."),
                        field("ping", "boolean", "Enable PLC4X ping", false, "false",
                                List.of("true", "false"), "advanced",
                                "Enable PLC4X driver ping/keepalive behavior when the remote route benefits from periodic reachability checks."),
                        field("pingTime", "number", "Ping interval (s)", false, "", null, "advanced",
                                "Ping interval in seconds when ping=true."),
                        field("retryTime", "number", "Retry time (s)", false, "", null, "advanced",
                                "PLC4X driver reconnect retry delay in seconds."),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced",
                                "Advanced override. When set, the generated host/port/rack/slot/controllerType fields are no longer the source of truth."),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced",
                                "Collector read timeout for PLC4X request futures."),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced",
                                "Fallback protocol timeout used when readTimeout is empty."))));
        registerPrimary(descriptor("BACNET_IP", "BACnet/IP", "BACnet/IP building automation protocol collector.",
                List.of("BACNET", "BACNETIP", "BACNET/IP"), BacnetIpCollector.class, "BACNET_IP", 47808, ProtocolAddressingMode.MIXED,
                true, true, true,
                List.of("analogInput:1.presentValue", "binaryOutput:3.presentValue", "device:1001.objectName"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "BACnet/IP target device host or IP address."),
                        field("port", "number", "Port", false, "47808", null, "connection",
                                "BACnet/IP UDP port. Leave empty to use the default 47808."),
                        field("localBindHost", "string", "Local bind host", false, "0.0.0.0", null, "connection",
                                "Local UDP bind host used by the BACnet/IP client."),
                        field("localBindPort", "number", "Local bind port", false, "", null, "connection",
                                "Local UDP bind port. Required when enabling COV subscription paths."),
                        field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol",
                                "Target BACnet device instance number."),
                        field("localDeviceInstance", "number", "Local device instance", false, "4194302", null, "protocol",
                                "Local BACnet client device instance used by the collector."),
                        field("useWhoIsDiscovery", "boolean", "Use Who-Is discovery", false, "false",
                                List.of("true", "false"), "protocol",
                                "Whether the adapter should issue Who-Is / I-Am discovery before normal polling."),
                        field("networkNumber", "number", "Network number", false, "", null, "protocol",
                                "Optional routed BACnet network number."),
                        field("macAddress", "string", "MAC address", false, "", null, "protocol",
                                "Optional routed-device MAC address hint."),
                        field("covEnabled", "boolean", "Enable COV subscription", false, "false",
                                List.of("true", "false"), "subscription",
                                "Whether COV subscription paths are enabled for this connection."),
                        field("defaultCovLifetimeSeconds", "number", "Default COV lifetime (s)", false, "300", null, "subscription",
                                "Default subscription lifetime used for COV renewals."),
                        field("defaultCovIncrement", "number", "Default COV increment", false, "", null, "subscription",
                                "Default COV increment threshold for analog objects."),
                        field("resubscribeOnReconnect", "boolean", "Resubscribe on reconnect", false, "true",
                                List.of("true", "false"), "subscription",
                                "Whether active subscriptions should be restored after reconnect."),
                        field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        field("maxPropertiesPerRequest", "number", "Max properties per request", false, "32", null, "advanced"),
                        field("readPropertyMultipleEnabled", "boolean", "Enable ReadPropertyMultiple", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("writePropertyMultipleEnabled", "boolean", "Enable WritePropertyMultiple", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("bbmdHost", "string", "BBMD host", false, "", null, "advanced"),
                        field("bbmdPort", "number", "BBMD port", false, "", null, "advanced"),
                        field("foreignDeviceTtlSeconds", "number", "Foreign device TTL (s)", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("BACNET_MSTP", "BACnet MS/TP",
                "BACnet MS/TP collector over RS485 with token passing, CRC framing and serial transport.",
                List.of("BACNETMSTP", "BACNET-MS/TP", "BACNET_MSTP"), BacnetMstpCollector.class, "BACNET_MSTP", null, ProtocolAddressingMode.MIXED,
                true, true, true,
                List.of("analogInput:1.presentValue", "device:1001.objectName", "128:42.512"),
                fields(
                        field("serialPort", "string", "Serial port", true, "COM1", null, "connection",
                                "RS485 serial port used by BACnet MS/TP transport."),
                        field("baudRate", "number", "Baud rate", false, "38400", null, "connection",
                                "MS/TP serial baud rate. Common values are 9600, 19200, 38400 or 76800."),
                        field("dataBits", "number", "Data bits", false, "8", null, "connection"),
                        field("stopBits", "number", "Stop bits", false, "1", null, "connection"),
                        field("parity", "select", "Parity", false, "none", List.of("none", "odd", "even"), "connection"),
                        field("localMacAddress", "number", "Local MAC address", true, "1", null, "protocol",
                                "Local BACnet MS/TP master MAC address on the token ring."),
                        field("remoteMacAddress", "number", "Remote MAC address", true, "2", null, "protocol",
                                "Target BACnet MS/TP remote MAC address used for confirmed requests."),
                        field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol",
                                "Target BACnet device instance number."),
                        field("remoteIsMaster", "boolean", "Remote is master", false, "true", List.of("true", "false"), "protocol"),
                        field("maxMaster", "number", "Max master", false, "127", null, "advanced"),
                        field("maxInfoFrames", "number", "Max info frames", false, "1", null, "advanced"),
                        field("nextStationMac", "number", "Next station MAC", false, "", null, "advanced"),
                        field("tokenClaimTimeoutMs", "number", "Token claim timeout (ms)", false, "1000", null, "advanced"),
                        field("pollForMasterTimeoutMs", "number", "Poll-for-master timeout (ms)", false, "250", null, "advanced"),
                        field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("BACNET_SC", "BACnet/SC",
                "Experimental BACnet/SC collector over secure WebSocket transport.",
                List.of("BACNETSC", "BACNET/SC", "BACNET-SC"), BacnetScCollector.class, "BACNET_SC", 443, ProtocolAddressingMode.MIXED,
                true, true, true,
                List.of("analogInput:1.presentValue", "device:1001.objectName", "128:42.512"),
                fields(
                        field("url", "string", "Secure WebSocket URL", false, "wss://127.0.0.1:443/bacnet/sc", null, "connection"),
                        field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "443", null, "connection"),
                        field("path", "string", "Path", false, "/bacnet/sc", null, "connection"),
                        field("remoteDeviceInstance", "number", "Remote device instance", true, "", null, "protocol"),
                        field("subprotocol", "string", "WebSocket subprotocol", false, "bacnet-sc", null, "protocol"),
                        field("keyStoreFile", "string", "客户端密钥库文件", true, "", null, "security"),
                        field("keyStoreType", "string", "客户端密钥库类型", false, "PKCS12", null, "security"),
                        field("keyStorePassword", "password", "客户端密钥库密码", true, "", null, "security"),
                        field("trustStoreFile", "string", "服务端信任库文件", true, "", null, "security"),
                        field("trustStoreType", "string", "服务端信任库类型", false, "PKCS12", null, "security"),
                        field("trustStorePassword", "password", "服务端信任库密码", true, "", null, "security"),
                        field("apduTimeout", "number", "APDU timeout (ms)", false, "5000", null, "advanced"),
                        field("segmentTimeout", "number", "Segment timeout (ms)", false, "2000", null, "advanced"),
                        field("retries", "number", "Retry count", false, "1", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("MITSUBISHI_MC", "Mitsubishi MC",
                "Self-owned Mitsubishi MC 3E Binary over TCP collector for polling read/write.",
                List.of("MC", "MELSEC_MC"), McCollector.class, "MITSUBISHI_MC", 5000, ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("M0", "X1A", "Y2F", "D100", "D100[4]", "R200", "W300", "ZR1000"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "Mitsubishi PLC IP address for MC over TCP."),
                        field("port", "number", "Port", false, "5000", null, "connection",
                                "MC TCP port. Leave empty to use the default 5000."),
                        field("networkNo", "number", "Network No", false, "0", null, "protocol",
                                "3E frame network number, usually 0 for a directly connected CPU."),
                        field("pcNo", "number", "PC No", false, "255", null, "protocol",
                                "3E frame PC number. The common default is 255."),
                        field("ioNo", "number", "I/O No", false, "1023", null, "protocol",
                                "3E frame destination I/O number. The common Ethernet default is 1023."),
                        field("stationNo", "number", "Station No", false, "0", null, "protocol",
                                "3E frame destination station number."),
                        field("monitoringTimer", "number", "Monitoring timer", false, "16", null, "advanced",
                                "MC monitoring timer in protocol units used inside each 3E request frame."),
                        field("frameType", "select", "Frame type", false, "3E_BINARY",
                                List.of("3E_BINARY", "3E_ASCII", "4E_BINARY"), "advanced",
                                "MC frame variant selector. The current stable path is 3E_BINARY. Other variants are reserved for staged rollout."),
                        field("randomReadEnabled", "boolean", "Enable random read", false, "false",
                                List.of("true", "false"), "advanced",
                                "Enable MC random-read strategy for sparse scalar word points. Continuous blocks still prefer batch read."),
                        field("maxRandomReadPoints", "number", "Max random-read points", false, "8", null, "advanced",
                                "Upper bound for one random-read request. Requests beyond this size fall back to normal plan-based reads."),
                        field("randomWriteEnabled", "boolean", "Enable random write", false, "false",
                                List.of("true", "false"), "advanced",
                                "Enable MC random-write strategy for sparse scalar word points. Continuous blocks still prefer batch writes."),
                        field("maxRandomWritePoints", "number", "Max random-write points", false, "8", null, "advanced",
                                "Upper bound for one random-write request. Requests beyond this size fall back to normal plan-based writes."),
                        field("maxWordsPerRequest", "number", "Max words per request", false, "120", null, "advanced",
                                "Collector-side guard rail for one word-unit batch request."),
                        field("maxBitsPerRequest", "number", "Max bits per request", false, "256", null, "advanced",
                                "Collector-side guard rail for one bit-unit batch request."),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced",
                                "Socket read timeout used while waiting for one MC response frame."),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced",
                                "Fallback timeout used when readTimeout is empty."))));
        registerPrimary(descriptor("OMRON_FINS", "OMRON FINS",
                "Self-owned OMRON FINS/UDP collector for polling read/write on common PLC memory areas.",
                List.of("FINS", "OMRONFINS"), OmronFinsCollector.class, "OMRON_FINS", 9600, ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("DM:100", "DM:100.3", "CIO:0.1", "WR:20", "HR:50", "EM0:100", "DM:200#8"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "OMRON PLC IP address for FINS/UDP communication."),
                        field("port", "number", "Port", false, "9600", null, "connection",
                                "FINS/UDP destination port. Leave empty to use the default 9600."),
                        field("plcNode", "number", "PLC node", true, "1", null, "protocol",
                                "Destination node number on the PLC side."),
                        field("localNode", "number", "Local node", true, "10", null, "protocol",
                                "Source node number used by the collector host."),
                        field("plcUnit", "number", "PLC unit", false, "0", null, "protocol",
                                "Destination unit number. CPU unit commonly uses 0."),
                        field("localUnit", "number", "Local unit", false, "0", null, "protocol",
                                "Source unit number used in the FINS header."),
                        field("plcNetwork", "number", "PLC network", false, "0", null, "protocol",
                                "Destination network number. Direct Ethernet access commonly uses 0."),
                        field("localNetwork", "number", "Local network", false, "0", null, "protocol",
                                "Source network number used in the FINS header."),
                        field("serviceIdSeed", "number", "Service ID seed", false, "1", null, "advanced",
                                "Initial FINS SID value used for request sequencing."),
                        field("batchReadEnabled", "boolean", "Enable batch read", false, "true",
                                List.of("true", "false"), "advanced",
                                "Enable protocol-level contiguous block read merging inside the collector."),
                        field("maxWordsPerRequest", "number", "Max words per request", false, "120", null, "advanced",
                                "Collector-side limit for one word-unit FINS read/write request."),
                        field("maxBitsPerRequest", "number", "Max bits per request", false, "256", null, "advanced",
                                "Collector-side limit for one bit-unit FINS read/write request."),
                        field("byteOrder", "select", "Byte order", false, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "advanced",
                                "Default byte order used for multi-byte numeric decoding when the point does not override it."),
                        field("wordOrder", "select", "Word order", false, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "advanced",
                                "Default word order used for 32-bit and 64-bit values when the point does not override it."),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced",
                                "UDP receive timeout while waiting for one FINS response."),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced",
                                "Fallback timeout used when readTimeout is empty."))));
        registerPrimary(descriptor("ETHERNET_IP", "EtherNet/IP", "PLC4X-backed EtherNet/IP / Logix tag collector.",
                List.of("EIP", "LOGIX", "AB_ETH"), EtherNetIpCollector.class, "ETHERNET_IP", 44818, ProtocolAddressingMode.SYMBOLIC,
                true, true, false,
                List.of("MainProgram.Tag1", "Program:MainProgram.Tag2", "%Tag[0]:1:DINT"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "44818", null, "connection"),
                        field("communicationPath", "string", "Communication path", false, "[1,0]", null, "protocol"),
                        field("backplane", "number", "Backplane", false, "1", null, "protocol"),
                        field("slot", "number", "Slot", false, "0", null, "protocol"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        field("bigEndian", "boolean", "Big-endian mode", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("forceUnconnectedOperation", "boolean", "Force unconnected operation", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("tcpKeepAlive", "boolean", "TCP keep-alive", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("tcpNoDelay", "boolean", "TCP no-delay", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("ADS", "Beckhoff ADS", "PLC4X-backed Beckhoff ADS / AMS collector.",
                List.of("AMS"), AdsCollector.class, "ADS", 48898, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("MAIN.temperature", "0x4020/0x0:REAL", "16416/32:STRING(80)"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "TCP port", false, "48898", null, "connection"),
                        field("targetAmsNetId", "string", "Target AMS Net ID", true, "", null, "protocol"),
                        field("targetAmsPort", "number", "Target AMS port", true, "851", null, "protocol"),
                        field("sourceAmsNetId", "string", "Source AMS Net ID", true, "", null, "protocol"),
                        field("sourceAmsPort", "number", "Source AMS port", true, "", null, "protocol"),
                        field("loadSymbolAndDataTypeTables", "boolean", "Load symbol/data type tables", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("timeoutRequest", "number", "ADS request timeout (ms)", false, "4000", null, "advanced"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("KNXNET_IP", "KNXnet/IP", "PLC4X-backed KNXnet/IP group address collector.",
                List.of("KNX", "KNXNETIP", "KNXNET/IP", "KNX_NET_IP"), KnxNetIpCollector.class, "KNXNET_IP", 3671, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("1/2/3:DPT1.001", "1/200:DPT9.001", "12345:DPT5.001"),
                fields(
                        field("host", "string", "Device host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "3671", null, "connection"),
                        field("groupAddressNumLevels", "number", "Group address levels", false, "3", null, "protocol"),
                        field("knxConnectionType", "select", "KNX connection type", false, "LINK_LAYER",
                                List.of("LINK_LAYER", "RAW", "BUSMONITOR"), "protocol"),
                        field("requestTimeout", "number", "PLC4X request timeout (ms)", false, "10000", null, "advanced"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "30", null, "advanced"),
                        field("knxprojFilePath", "string", "KNX project file path", false, "", null, "advanced"),
                        field("knxprojPassword", "password", "KNX project password", false, "", null, "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "10000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "10000", null, "advanced"))));
        registerPrimary(descriptor("OPC_DA", "OPC DA", "OPC DA access through local or bridge mode.",
                List.of(), OpcDaCollector.class, "OPC_DA", null, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("Channel1.Device1.Tag1", "Random.Real8"),
                fields(
                        field("host", "string", "Host", true, "127.0.0.1", null, "connection"),
                        field("serverProgId", "string", "Server ProgID", true, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("progId", "string", "ProgID alias", false, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("clsid", "string", "CLSID alias", false, "", null, "connection"),
                        field("bridgeMode", "select", "Bridge mode", true, "INMEMORY",
                                List.of("INMEMORY", "HTTP"), "bridge"),
                        conditional("bridgeBaseUrl", "string", "Bridge base URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge", "bridgeMode=HTTP"),
                        field("url", "string", "Bridge or access URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge"),
                        field("bridgeToken", "password", "Bridge token", false, "", null, "bridge"),
                        field("bridgeRetryCount", "number", "Bridge retry count", false, "1", null, "advanced"),
                        field("bridgeRetryBackoffMs", "number", "Bridge retry backoff (ms)", false, "200", null, "advanced"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("domain", "string", "Windows domain", false, "", null, "security"),
                        field("requestTimeout", "number", "Request timeout (ms)", false, "5000", null, "advanced"),
                        field("updateRate", "number", "Subscription refresh interval (ms)", false, "1000", null, "advanced"))));
        registerPrimary(descriptor("OPC_UA", "OPC UA", "PLC4X-backed OPC Unified Architecture collector.",
                List.of("OPCUA"), Plc4xOpcUaCollector.class, "OPC_UA", 4840, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001", "ns=3;i=1001;REAL"),
                opcUaFields()));
        registerPrimary(descriptor("OPC_UA_PLC4X", "OPC UA (PLC4X Alias)",
                "Legacy alias for the PLC4X OPC UA collector kept for backward compatibility.",
                List.of("OPCUA_PLC4X"), Plc4xOpcUaCollector.class, "OPC_UA_PLC4X", 4840, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001;REAL"),
                opcUaFields()));
        registerPrimary(descriptor("OPC_UA_MILO", "OPC UA（Milo 实验驱动）",
                "使用 Eclipse Milo 的独立 OPC UA 客户端，需单独完成实服契约测试后再用于生产。",
                List.of("OPCUA_MILO"), OpcUaCollector.class, "OPC_UA_MILO", 4840, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001"),
                opcUaMiloFields()));
        registerPrimary(descriptor("SNMP", "SNMP", "SNMP polling protocol.",
                List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3"), SnmpCollector.class, "SNMP", 161, ProtocolAddressingMode.NUMERIC,
                true, true, true,
                List.of("1.3.6.1.2.1.1.3.0", "1.3.6.1.4.1.2021.10.1.3.1"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "161", null, "connection"),
                        field("community", "string", "Community", true, "public", null, "security"),
                        field("snmpVersion", "select", "SNMP version", true, "2c",
                                List.of("1", "2c", "3"), "protocol"),
                        conditional("snmpSecurityName", "string", "SNMPv3 security name", false, "", null,
                                "security", "snmpVersion=3"),
                        conditional("snmpSecurityLevel", "select", "SNMPv3 security level", false, "authPriv",
                                List.of("noAuthNoPriv", "authNoPriv", "authPriv"), "security", "snmpVersion=3"),
                        conditional("snmpAuthProtocol", "select", "SNMPv3 auth protocol", false, "SHA",
                                List.of("MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"),
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpAuthPassword", "password", "SNMPv3 auth password", false, "", null,
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpPrivProtocol", "select", "SNMPv3 privacy protocol", false, "AES128",
                                List.of("DES", "AES128", "AES192", "AES256", "NONE"),
                                "security", "snmpSecurityLevel=authPriv"),
                        conditional("snmpPrivPassword", "password", "SNMPv3 privacy password", false, "", null,
                                "security", "snmpSecurityLevel=authPriv"),
                        conditional("snmpContextName", "string", "SNMPv3 context name", false, "", null,
                                "security", "snmpVersion=3"),
                        conditional("snmpContextEngineId", "string", "SNMPv3 context engine ID", false, "", null,
                                "security", "snmpVersion=3"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"),
                        field("snmpRetries", "number", "Retry count", false, "1", null, "advanced"))));
        registerPrimary(descriptor("COAP", "CoAP", "CoAP request/response collection protocol.",
                List.of("COAP_SSL"), CoapCollector.class, "COAP", 5683, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("/sensors/temp", "coap://device.local/sensors/humidity"),
                fields(
                        field("url", "string", "CoAP base URL", false, "coap://127.0.0.1:5683", null, "connection"),
                        field("host", "string", "Device host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "5683", null, "connection"),
                        field("scheme", "select", "Scheme", false, "coap", List.of("coap", "coaps"), "connection"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced"),
                        field("maxPendingMessages", "number", "Max pending requests", false, "1024", null, "advanced"),
                        field("dispatchBatchSize", "number", "Dispatch batch size", false, "1", null, "advanced"),
                        field("dispatchFlushInterval", "number", "Dispatch flush interval (ms)", false, "0", null, "advanced"),
                        field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced"))));
        registerPrimary(descriptor("MQTT", "MQTT", "MQTT subscription/publish collection protocol.",
                List.of("MQTT_SSL"), MqttCollector.class, "MQTT", 1883, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("devices/${deviceId}/temperature", "factory/line1/+/status"),
                fields(
                        field("url", "string", "Broker URL", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("brokerUrl", "string", "Broker URL alias", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("host", "string", "Broker host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Broker port", false, "1883", null, "connection"),
                        field("clientId", "string", "Client ID", true, "device_mqtt", null, "connection"),
                        field("version", "select", "MQTT version", true, "v5", List.of("v5", "v3"), "connection"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("sslEnabled", "boolean", "Enable SSL", false, "false",
                                List.of("true", "false"), "security"),
                        field("subscribeTopics", "string", "Default subscribe topics", false,
                                "devices/${deviceId}/#", null, "topic"),
                        field("subscribeQos", "select", "Default subscribe QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        field("publishTopic", "string", "Default publish topic", false,
                                "devices/${deviceId}/data", null, "topic"),
                        field("publishQos", "select", "Publish QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        field("retained", "boolean", "Retained publish flag", false, "false",
                                List.of("true", "false"), "topic"),
                        field("cleanSession", "boolean", "Clean session", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("autoReconnect", "boolean", "Auto reconnect", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("sessionExpiryInterval", "number", "Session expiry interval (s)", false, "86400", null, "advanced"),
                        field("receiveMaximum", "number", "Receive maximum", false, "65535", null, "advanced"),
                        field("willTopic", "string", "Will topic", false, "", null, "topic"),
                        field("willMessage", "string", "Will message", false, "", null, "topic"),
                        field("willQos", "select", "Will QoS", false, "0",
                                List.of("0", "1", "2"), "topic"),
                        field("willRetained", "boolean", "Will retained flag", false, "false",
                                List.of("true", "false"), "topic"),
                        field("authTopic", "string", "Auth topic", false, "", null, "security"),
                        field("messageProperties", "object", "MQTT v5 message properties", false, "{}", null, "advanced"),
                        field("maxPendingMessages", "number", "Max pending messages", false, "5000", null, "advanced"),
                        field("dispatchBatchSize", "number", "Dispatch batch size", false, "1", null, "advanced"),
                        field("dispatchFlushInterval", "number", "Dispatch flush interval (ms)", false, "0", null, "advanced"),
                        field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced"),
                        field("productKey", "string", "Product key", false, "", null, "security"),
                        field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        field("authParams", "object", "Extended auth params", false, "{}", null, "security"))));
        registerPrimary(descriptor("IEC104", "IEC 60870-5-104", "IEC104 telemetry collection.",
                List.of("IEC_104"), Iec104Collector.class, "IEC104", 2404, ProtocolAddressingMode.NUMERIC,
                true, true, true,
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "2404", null, "connection"),
                        field("slaveId", "number", "Common address", true, "1", null, "protocol"),
                        field("timeout", "number", "Protocol timeout (ms)", true, "5000", null, "advanced"))));
        registerPrimary(descriptor("DLT645_2007", "DL/T 645-2007", "电能表串行通信数据采集。",
                List.of("DLT645", "DL_T_645", "DLT_645_2007"), Dlt645Collector.class, "DLT645_2007", null,
                ProtocolAddressingMode.SYMBOLIC, true, true, false,
                List.of("00010000", "02010100"),
                fields(
                        field("serialPort", "string", "串口名称", true, "COM1", null, "connection"),
                        field("baudRate", "number", "波特率", true, "2400", null, "connection"),
                        field("dataBits", "number", "数据位", true, "8", null, "connection"),
                        field("stopBits", "number", "停止位", true, "1", null, "connection"),
                        field("parity", "select", "校验位", true, "EVEN", List.of("NONE", "EVEN", "ODD"), "connection"),
                        field("meterAddress", "string", "电表通信地址", true, "000000000001", null, "protocol"),
                        field("readTimeout", "number", "读取超时（毫秒）", false, "3000", null, "advanced"),
                        field("writeTimeout", "number", "写入超时（毫秒）", false, "3000", null, "advanced"),
                        field("retryCount", "number", "重试次数", false, "2", null, "advanced"),
                        field("wakeupByteCount", "number", "唤醒字节数", false, "4", null, "advanced"),
                        field("interFrameDelayMs", "number", "帧间隔（毫秒）", false, "20", null, "advanced"),
                        field("writeEnabled", "boolean", "允许远程写入", false, "false", List.of("true", "false"), "security"),
                        field("writePasswordHex", "password", "写入密码（十六进制）", false, "", null, "security"),
                        field("operatorCodeHex", "password", "操作者代码（十六进制）", false, "", null, "security"))));
        registerPrimary(descriptor("IEC101", "IEC 60870-5-101", "非平衡式主站串行遥测与遥控。",
                List.of("IEC_101", "IEC60870_5_101"), Iec101Collector.class, "IEC101", null,
                ProtocolAddressingMode.MIXED, true, true, true,
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                fields(
                        field("serialPort", "string", "串口名称", true, "COM1", null, "connection"),
                        field("baudRate", "number", "波特率", true, "9600", null, "connection"),
                        field("dataBits", "number", "数据位", true, "8", null, "connection"),
                        field("stopBits", "number", "停止位", true, "1", null, "connection"),
                        field("parity", "select", "校验位", true, "EVEN", List.of("NONE", "EVEN", "ODD"), "connection"),
                        field("linkMode", "select", "链路模式", true, "UNBALANCED", List.of("UNBALANCED"), "protocol"),
                        field("linkAddress", "number", "链路地址", true, "1", null, "protocol"),
                        field("commonAddress", "number", "公共地址", true, "1", null, "protocol"),
                        field("linkAddressSize", "select", "链路地址长度", true, "1", List.of("1", "2"), "protocol"),
                        field("causeOfTransmissionSize", "select", "传送原因长度", true, "2", List.of("1", "2"), "protocol"),
                        field("commonAddressSize", "select", "公共地址长度", true, "2", List.of("1", "2"), "protocol"),
                        field("informationObjectAddressSize", "select", "信息体地址长度", true, "3", List.of("1", "2", "3"), "protocol"),
                        field("readTimeout", "number", "读取超时（毫秒）", false, "3000", null, "advanced"),
                        field("retryCount", "number", "重试次数", false, "2", null, "advanced"),
                        field("interFrameDelayMs", "number", "帧间隔（毫秒）", false, "20", null, "advanced"),
                        field("class1PollIntervalMs", "number", "一级数据轮询周期（毫秒）", false, "1000", null, "subscription"),
                        field("class2PollIntervalMs", "number", "二级数据轮询周期（毫秒）", false, "5000", null, "subscription"),
                        field("generalInterrogationOnConnect", "boolean", "连接后总召唤", false, "true", List.of("true", "false"), "subscription"),
                        field("clockSyncOnConnect", "boolean", "连接后时钟同步", false, "false", List.of("true", "false"), "subscription"))));
        registerPrimary(descriptor("IEC61850", "IEC 61850", "IEC61850 MMS collection.",
                List.of("IEC_61850"), Iec61850Collector.class, "IEC61850", 102, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("LD0/MMXU1.A.phsA.cVal.mag.f"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "MMS port", true, "102", null, "connection"),
                        field("timeout", "number", "Protocol timeout (ms)", true, "10000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "10000", null, "advanced"))));
        registerPrimary(descriptor("HTTP", "HTTP", "HTTP polling and request based collection.",
                List.of("HTTPS"), HttpCollector.class, "HTTP", 80, ProtocolAddressingMode.SYMBOLIC,
                true, true, false,
                List.of("/api/data", "http://device.local/status"),
                fields(
                        field("url", "string", "HTTP base URL", false, "http://127.0.0.1:8080", null, "connection"),
                        field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "Enable HTTPS", false, "false",
                                List.of("true", "false"), "security"),
                        field("path", "string", "Base path", false, "", null, "request"),
                        field("method", "select", "Request method", false, "POST",
                                List.of("GET", "POST", "PUT", "DELETE", "HEAD"), "request"),
                        field("headers", "object", "Request headers", false, "{}", null, "request"),
                        field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        field("sendEndpoint", "string", "Send endpoint", false, "/api/data", null, "request"),
                        field("receiveEndpoint", "string", "Receive endpoint", false, "/api/receive", null, "request"),
                        field("receiveMethod", "select", "Receive method", false, "GET",
                                List.of("GET", "POST", "PUT", "DELETE"), "request"),
                        field("healthCheckPath", "string", "Health check path", false, "/health", null, "advanced"),
                        field("heartbeatEndpoint", "string", "Heartbeat endpoint", false, "/health", null, "advanced"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("authToken", "password", "Bearer token", false, "", null, "security"),
                        field("authEndpoint", "string", "Auth endpoint", false, "/api/auth", null, "security"),
                        field("authMethod", "select", "Auth method", false, "POST",
                                List.of("GET", "POST", "PUT", "DELETE"), "security"),
                        field("proxyHost", "string", "Proxy host", false, "", null, "advanced"),
                        field("proxyPort", "number", "Proxy port", false, "8080", null, "advanced"),
                        field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        field("authParams", "object", "Extended auth params", false, "{}", null, "security"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"))));
        registerPrimary(descriptor("WEBSOCKET", "WebSocket", "WebSocket collection protocol.",
                List.of("WEBSOCKET_SSL"), WebSocketCollector.class, "WEBSOCKET", 80, ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("ws://127.0.0.1:8080/ws", "/ws/device"),
                fields(
                        field("url", "string", "WebSocket URL", false, "ws://127.0.0.1:8080/ws", null, "connection"),
                        field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "Enable WSS", false, "false",
                                List.of("true", "false"), "security"),
                        field("path", "string", "Connect path", false, "/ws", null, "connection"),
                        field("headers", "object", "Request headers", false, "{}", null, "request"),
                        field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("authToken", "password", "Bearer token", false, "", null, "security"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("writeTimeout", "number", "Write timeout (ms)", false, "5000", null, "advanced"),
                        field("subprotocol", "string", "Subprotocol", false, "collector-v1", null, "advanced"),
                        field("binaryMode", "boolean", "Binary mode", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        field("heartbeatMessage", "string", "Heartbeat message", false, "ping", null, "advanced"),
                        field("heartbeatUsePing", "boolean", "Use ping frame", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("authWaitResponse", "boolean", "Wait for auth response", false, "true",
                                List.of("true", "false"), "security"),
                        field("productKey", "string", "Product key", false, "", null, "security"),
                        field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        field("authParams", "object", "Extended auth params", false, "{}", null, "security"))));
        registerPrimary(descriptor("CUSTOM_TCP", "Custom TCP",
                "受控模板和帧编解码驱动的自定义TCP请求响应协议。",
                List.of(), CustomProtocolCollector.class, "CUSTOM_TCP", null, ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("BYTE:0:2", "BIT:2:3", "JSON:$.data.value"),
                customConnectionFields(false)));
        registerPrimary(descriptor("CUSTOM_UDP", "Custom UDP",
                "以单个数据报为完整帧的自定义UDP请求响应协议。",
                List.of(), CustomProtocolCollector.class, "CUSTOM_UDP", null, ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("BYTE:0:4", "BIT:4:0", "JSON:$.value"),
                customConnectionFields(true)));

        registerAlias("HTTPS", "HTTP", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 443);
        });
        registerAlias("WEBSOCKET_SSL", "WEBSOCKET", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 443);
        });
        registerAlias("MQTT_SSL", "MQTT", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 8883);
        });
        registerAlias("COAP_SSL", "COAP", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 5684);
            putExtIfAbsent(cfg, "scheme", "coaps");
        });
        registerAlias("SNMP_V1", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "1"));
        registerAlias("SNMP_V2C", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "2c"));
        registerAlias("SNMP_V3", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "3"));
    }

    public boolean supports(String protocol) {
        return canonicalProtocol(protocol) != null;
    }

    public String canonicalProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        String normalized = normalize(protocol);
        if (descriptors.containsKey(normalized)) {
            return normalized;
        }
        AliasDescriptor alias = aliases.get(normalized);
        return alias != null ? alias.primaryCode() : null;
    }

    public ProtocolDescriptor resolve(String protocol) {
        String canonical = canonicalProtocol(protocol);
        return canonical == null ? null : descriptors.get(canonical);
    }

    public Collection<ProtocolDescriptor> primaryDescriptors() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    public Collection<ProtocolDescriptor> allDescriptorsIncludingAliases() {
        LinkedHashMap<String, ProtocolDescriptor> resolved = new LinkedHashMap<>();
        for (ProtocolDescriptor descriptor : descriptors.values()) {
            resolved.put(descriptor.code(), descriptor);
        }
        for (Map.Entry<String, AliasDescriptor> entry : aliases.entrySet()) {
            ProtocolDescriptor primary = descriptors.get(entry.getValue().primaryCode());
            if (primary != null) {
                resolved.putIfAbsent(entry.getKey(), primary);
            }
        }
        return Collections.unmodifiableCollection(resolved.values());
    }

    public Collection<String> allSupportedCodes() {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(descriptors.keySet());
        codes.addAll(aliases.keySet());
        return Collections.unmodifiableSet(codes);
    }

    public String applyConnectionDefaults(String protocol, DeviceConnection cfg) {
        ProtocolDescriptor descriptor = resolve(protocol);
        String canonical = descriptor != null ? descriptor.connectionType() : normalize(protocol);
        AliasDescriptor alias = aliases.get(normalize(protocol));
        if (alias != null && alias.customizer() != null) {
            alias.customizer().accept(cfg);
        }
        if (descriptor != null) {
            applyDefaultPort(cfg, descriptor.defaultPort());
        }
        return canonical;
    }

    public ProtocolSchema toSchema(String protocol) {
        ProtocolDescriptor descriptor = resolve(protocol);
        if (descriptor == null) {
            return null;
        }
        String canonical = descriptor.code();
        return ProtocolSchema.builder()
                .protocol(canonical)
                .title(descriptor.title())
                .description(descriptor.description())
                .implemented(descriptor.implemented())
                .writable(descriptor.writable())
                .subscribable(descriptor.subscribable())
                .implementationState(descriptor.implementationState())
                .writeCapability(descriptor.writeCapability())
                .subscriptionCapability(descriptor.subscriptionCapability())
                .browseCapability(descriptor.browseCapability())
                .aliases(descriptor.aliases())
                .pointAddressHints(descriptor.pointAddressHints())
                .dataTypes(resolveDataTypes(canonical))
                .typeMode(resolveTypeMode(canonical))
                .primaryTypeField(resolvePrimaryTypeField(canonical))
                .platformDataTypeMode(resolvePlatformDataTypeMode(canonical))
                .driverTypeEnabled(resolveDriverTypeEnabled(canonical))
                .driverTypeLabel(resolveDriverTypeLabel(canonical))
                .driverTypeField(resolveDriverTypeField(canonical))
                .driverDataTypes(resolveDriverDataTypes(canonical))
                .pointFields(resolvePointFields(canonical))
                .connectionFields(resolveConnectionFields(descriptor))
                .build();
    }

    private List<ProtocolFieldConfig> resolveConnectionFields(ProtocolDescriptor descriptor) {
        if (!descriptor.subscribable()) {
            return descriptor.connectionFields();
        }
        boolean alreadyConfigured = descriptor.connectionFields().stream()
                .anyMatch(item -> "subscriptionFallbackStrategy".equals(item.getName()));
        if (alreadyConfigured) {
            return descriptor.connectionFields();
        }
        List<ProtocolFieldConfig> resolved = new java.util.ArrayList<>(descriptor.connectionFields());
        resolved.add(field("subscriptionFallbackStrategy", "select", "订阅不可用处理策略", false,
                "FAIL_FAST", List.of("FAIL_FAST", "FALLBACK_TO_POLLING"), "advanced",
                "驱动或设备不支持订阅时，可选择立即失败或继续使用现有轮询采集。"));
        return List.copyOf(resolved);
    }

    private List<String> resolveDataTypes(String protocol) {
        return switch (protocol) {
            case "MODBUS_TCP", "MODBUS_RTU" -> MODBUS_DATA_TYPES;
            default -> EXTENDED_DATA_TYPES;
        };
    }

    private ProtocolTypeMode resolveTypeMode(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7", "MITSUBISHI_MC", "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "ETHERNET_IP", "ADS", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "SNMP" -> ProtocolTypeMode.DRIVER_PRIMARY;
            case "KNXNET_IP" -> ProtocolTypeMode.PROTOCOL_FIELD_PRIMARY;
            default -> ProtocolTypeMode.PLATFORM_ONLY;
        };
    }

    private String resolvePrimaryTypeField(String protocol) {
        return switch (resolveTypeMode(protocol)) {
            case DRIVER_PRIMARY -> "additionalConfig.driverDataType";
            case PROTOCOL_FIELD_PRIMARY -> "additionalConfig.dptId";
            case PLATFORM_ONLY -> "dataType";
        };
    }

    private PlatformDataTypeMode resolvePlatformDataTypeMode(String protocol) {
        return switch (resolveTypeMode(protocol)) {
            case DRIVER_PRIMARY, PROTOCOL_FIELD_PRIMARY -> PlatformDataTypeMode.DERIVED_EDITABLE;
            case PLATFORM_ONLY -> PlatformDataTypeMode.REQUIRED;
        };
    }

    private boolean resolveDriverTypeEnabled(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7", "MITSUBISHI_MC", "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "ETHERNET_IP", "ADS", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "SNMP" -> true;
            default -> false;
        };
    }

    private String resolveDriverTypeLabel(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7" -> "S7 driver type";
            case "MITSUBISHI_MC" -> "MC driver type";
            case "BACNET_IP" -> "BACnet driver type";
            case "BACNET_MSTP" -> "BACnet MS/TP driver type";
            case "BACNET_SC" -> "BACnet/SC driver type";
            case "ETHERNET_IP" -> "EIP driver type";
            case "ADS" -> "ADS driver type";
            case "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO" -> "OPC UA 驱动数据类型";
            case "SNMP" -> "SNMP value type";
            default -> null;
        };
    }

    private String resolveDriverTypeField(String protocol) {
        return resolveDriverTypeEnabled(protocol) ? "additionalConfig.driverDataType" : null;
    }

    private List<String> resolveDriverDataTypes(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7" -> List.of(
                    "BOOL", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT", "LINT", "ULINT",
                    "REAL", "LREAL", "CHAR", "WCHAR", "STRING", "WSTRING",
                    "TIME", "LTIME", "DATE", "TIME_OF_DAY", "DATE_AND_TIME", "S5TIME");
            case "MITSUBISHI_MC" -> List.of(
                    "BOOL", "INT16", "UINT16", "INT32", "UINT32", "FLOAT32", "FLOAT64", "STRING");
            case "BACNET_IP", "BACNET_MSTP", "BACNET_SC" -> List.of(
                    "AUTO", "BOOLEAN", "UNSIGNED", "SIGNED", "REAL", "DOUBLE", "ENUM", "STRING", "BIT_STRING");
            case "ETHERNET_IP" -> List.of(
                    "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "WORD",
                    "DINT", "UDINT", "DWORD", "LINT", "ULINT", "LWORD", "REAL", "LREAL", "STRING");
            case "ADS" -> List.of(
                    "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT",
                    "LINT", "ULINT", "REAL", "LREAL", "STRING", "WSTRING");
            case "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO" -> List.of(
                    "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT",
                    "LINT", "ULINT", "REAL", "LREAL", "CHAR", "WCHAR", "STRING",
                    "TIME", "DATE", "DATE_AND_TIME");
            case "SNMP" -> List.of(
                    "AUTO", "INTEGER", "COUNTER32", "COUNTER64", "GAUGE32", "TIMETICKS",
                    "OCTET_STRING", "STRING", "IP_ADDRESS", "OID", "NULL");
            default -> Collections.emptyList();
        };
    }

    private List<ProtocolFieldConfig> resolvePointFields(String protocol) {
        return switch (protocol) {
            case "MODBUS_TCP", "MODBUS_RTU" -> modbusPointFields();
            case "SIEMENS_S7" -> s7PointFields();
            case "MITSUBISHI_MC" -> mcPointFields();
            case "OMRON_FINS" -> finsPointFields();
            case "BACNET_IP", "BACNET_MSTP", "BACNET_SC" -> bacnetPointFields();
            case "ETHERNET_IP" -> etherNetIpPointFields();
            case "ADS" -> adsPointFields();
            case "KNXNET_IP" -> knxPointFields();
            case "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO" -> opcUaPointFields();
            case "OPC_DA" -> opcDaPointFields();
            case "MQTT" -> mqttPointFields();
            case "IEC104" -> iec104PointFields();
            case "DLT645_2007" -> dlt645PointFields();
            case "IEC101" -> iec101PointFields();
            case "COAP" -> coapPointFields();
            case "CUSTOM_TCP", "CUSTOM_UDP" -> customPointFields();
            default -> Collections.emptyList();
        };
    }

    private List<ProtocolFieldConfig> customPointFields() {
        return List.of(
                pointField("additionalConfig.requestTemplate", "textarea", "读取请求模板", false, "",
                        Collections.emptyList(), "可覆盖连接级读取模板，仅支持固定占位符，不执行脚本。", null),
                pointField("additionalConfig.writeRequestTemplate", "textarea", "写入请求模板", false, "",
                        Collections.emptyList(), "写入模板可使用value和valueHex等受控占位符。", null),
                pointField("additionalConfig.requestAddress", "string", "协议请求地址", false, "",
                        Collections.emptyList(), "与响应解析地址分离的设备原始地址。", null),
                pointField("additionalConfig.addressHex", "string", "请求地址十六进制", false, "",
                        Collections.emptyList(), "直接写入模板addressHex占位符的十六进制内容。", null),
                pointField("additionalConfig.byteOrder", "select", "字节序", false, "BIG_ENDIAN",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "数值解析和valueHex编码使用的字节序。", null),
                pointField("additionalConfig.length", "number", "解析长度", false, "",
                        Collections.emptyList(), "字符串或变长字段的解析字节数。", null),
                pointField("additionalConfig.charset", "string", "字符集", false, "UTF-8",
                        Collections.emptyList(), "文本模板、字符串值和JSON响应使用的字符集。", null),
                pointField("additionalConfig.writeExpectResponse", "boolean", "写入等待响应", false, "true",
                        List.of("true", "false"), "关闭后写入仅发送请求，不等待响应。", null),
                pointField("additionalConfig.writeSuccessHex", "string", "写入成功响应前缀", false, "",
                        Collections.emptyList(), "配置后仅响应十六进制以前缀开头时判定成功。", null)
        );
    }

    private List<ProtocolFieldConfig> modbusPointFields() {
        return List.of(
                pointField("additionalConfig.registerType", "select", "Register type", false, "",
                        List.of("HOLDING_REGISTER", "INPUT_REGISTER", "COIL", "DISCRETE_INPUT"),
                        "Optional explicit Modbus register family. Usually inferred from the address format.", null),
                pointField("additionalConfig.byteOrder", "select", "Byte order", false, "BIG_ENDIAN",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"),
                        "Byte order override for multi-byte numeric decoding.", null),
                pointField("additionalConfig.wordOrder", "select", "Word order", false, "",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"),
                        "Word order override for 32-bit and 64-bit register values.", null),
                pointField("additionalConfig.bitIndex", "number", "Bit index", false, "",
                        Collections.emptyList(), "Bit position inside the selected register when a packed bit is addressed.", null),
                pointField("additionalConfig.functionCode", "number", "Function code", false, "",
                        Collections.emptyList(), "Compatibility override for specialized Modbus function-code routing.", null),
                pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Used when dataType=STRING to declare the payload length.", "dataType=STRING")
        );
    }

    private List<ProtocolFieldConfig> s7PointFields() {
        return List.of(
                pointField("additionalConfig.subscriptionMode", "select", "Subscription mode", false, "",
                        List.of("CYCLIC", "MODE", "SYS", "USR", "ALM"),
                        "Used when collectionMode=SUBSCRIPTION or EVENT. CYCLIC uses the point's absolute address. MODE/SYS/USR/ALM register S7 event subscriptions.", null),
                pointField("additionalConfig.subscriptionAddress", "string", "Subscription address", false, "",
                        Collections.emptyList(), "Optional PLC4X subscription address override. For MODE/SYS/USR/ALM it usually matches the mode token; leave empty to reuse the point address or selected mode.", null),
                pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Used when driverDataType=STRING or WSTRING to declare the PLC string length.", "driverDataType=STRING/WSTRING"),
                pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Optional one-dimensional array length when the address does not already include [n]. Only full-array read/write is supported.", null)
        );
    }

    private List<ProtocolFieldConfig> mcPointFields() {
        return List.of(
                pointField("additionalConfig.bitIndex", "number", "Bit index", false, "",
                        Collections.emptyList(), "Optional bit offset inside one word device. You can use either D100.3 syntax or address=D100 with additionalConfig.bitIndex=3. Only BOOL points are supported.", "dataType=BOOLEAN/driverDataType=BOOL"),
                pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Required when driverDataType=STRING. The value is the string character length, and the collector allocates the corresponding MC word span.", "driverDataType=STRING"),
                pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Optional one-dimensional array length when the address does not already include [n]. BOOL arrays use bit-unit batches, numeric arrays use word-unit batches.", null)
        );
    }

    private List<ProtocolFieldConfig> finsPointFields() {
        return List.of(
                pointField("additionalConfig.bitIndex", "number", "Bit index", false, "",
                        Collections.emptyList(), "Optional bit offset override. You can use either address=DM:100.3 or address=DM:100 with additionalConfig.bitIndex=3. Only BOOL points are supported.", "dataType=BOOLEAN"),
                pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Required when dataType=STRING and the address does not already use #length.", "dataType=STRING"),
                pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Optional one-dimensional array length for word-based numeric arrays. Bit arrays are not supported in P0.", null),
                pointField("additionalConfig.byteOrder", "select", "Byte order", false, "",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "Optional per-point byte-order override for multi-byte numeric values.", null),
                pointField("additionalConfig.wordOrder", "select", "Word order", false, "",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "Optional per-point word-order override for 32-bit and 64-bit values.", null)
        );
    }
    private List<ProtocolFieldConfig> bacnetPointFields() {
        return List.of(
                pointField("additionalConfig.driverDataType", "select", "BACnet driver type", false, "AUTO",
                        resolveDriverDataTypes("BACNET_IP"),
                        "BACnet native value-type hint used for decoding enums, bit strings, numeric values, and strings.", null),
                pointField("additionalConfig.arrayIndex", "number", "Array index", false, "",
                        Collections.emptyList(), "Optional BACnet property array index when the address does not already include [n].", null),
                pointField("additionalConfig.writePriority", "number", "Write priority", false, "",
                        Collections.emptyList(), "Optional BACnet write priority for commandable presentValue writes.", null),
                pointField("additionalConfig.covMode", "select", "COV mode", false, "",
                        List.of("OBJECT", "PROPERTY"),
                        "Optional subscription mode used by SubscribeCOV or SubscribeCOVProperty flows.", null),
                pointField("additionalConfig.covIncrement", "number", "COV increment", false, "",
                        Collections.emptyList(), "Optional per-point COV increment override for analog objects.", null)
        );
    }
    private List<ProtocolFieldConfig> etherNetIpPointFields() {
        return List.of(
                pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Element count for array tags when the address or symbol refers to an array.", null)
        );
    }

    private List<ProtocolFieldConfig> adsPointFields() {
        return List.of(
                pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Used when driverDataType=STRING or WSTRING to declare the ADS string length.", "driverDataType=STRING/WSTRING"),
                pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Element count for ADS array symbols or direct array addresses.", null)
        );
    }

    private List<ProtocolFieldConfig> knxPointFields() {
        return List.of(
                pointField("additionalConfig.dptId", "string", "DPT id", false, "",
                        Collections.emptyList(), "KNX datapoint type identifier. More precise than the platform dataType for wire-level decoding.", null),
                pointField("additionalConfig.dpt", "string", "DPT alias", false, "",
                        Collections.emptyList(), "Compatibility alias for older KNX DPT configurations.", null)
        );
    }

    private List<ProtocolFieldConfig> opcUaPointFields() {
        return List.of(
                pointField("additionalConfig.nodeId", "string", "NodeId", false, "",
                        Collections.emptyList(), "Explicit OPC UA NodeId. When empty, the collector can still use address directly.", null),
                pointField("additionalConfig.namespace", "number", "Namespace", false, "",
                        Collections.emptyList(), "Used together with identifier to build a NodeId.", null),
                pointField("additionalConfig.identifier", "string", "Identifier", false, "",
                        Collections.emptyList(), "Node identifier used together with namespace.", null),
                pointField("additionalConfig.identifierType", "select", "Identifier type", false, "STRING",
                        List.of("STRING", "NUMERIC", "GUID", "OPAQUE"),
                        "Encoding type for the OPC UA NodeId identifier.", null),
                pointField("additionalConfig.samplingInterval", "number", "Sampling interval (ms)", false, "",
                        Collections.emptyList(), "Sampling interval used for subscriptions or monitored items.", null),
                pointField("additionalConfig.publishingInterval", "number", "Publishing interval (ms)", false, "",
                        Collections.emptyList(), "Publishing interval used for subscriptions.", null),
                pointField("additionalConfig.queueSize", "number", "Queue size", false, "",
                        Collections.emptyList(), "Subscription queue size for buffered notifications.", null),
                pointField("additionalConfig.arraySize", "number", "数组长度", false, "",
                        Collections.emptyList(), "OPC UA 一维数组节点的元素数量，标量点位保持为空。", null),
                pointField("additionalConfig.subscribe", "boolean", "Subscribe", false, "",
                        List.of("true", "false"), "Whether this point should use subscription mode.", null),
                pointField("additionalConfig.monitor", "boolean", "Monitor alias", false, "",
                        List.of("true", "false"), "Compatibility alias for older monitored-item configurations.", null)
        );
    }

    private List<ProtocolFieldConfig> opcDaPointFields() {
        return List.of(
                pointField("additionalConfig.itemId", "string", "Item ID", false, "",
                        Collections.emptyList(), "OPC DA item identifier. When empty, address is used directly.", null),
                pointField("additionalConfig.itemPath", "string", "Item path", false, "",
                        Collections.emptyList(), "Optional OPC DA item path.", null),
                pointField("additionalConfig.dataSource", "select", "Data source", false, "DEVICE",
                        List.of("DEVICE", "CACHE"), "Whether reads should use device data or the OPC cache.", null)
        );
    }

    private List<ProtocolFieldConfig> mqttPointFields() {
        return List.of(
                pointField("additionalConfig.topic", "string", "Topic", false, "",
                        Collections.emptyList(), "MQTT subscribe topic. When empty, address is used as the topic.", null),
                pointField("additionalConfig.writeTopic", "string", "Write topic", false, "",
                        Collections.emptyList(), "MQTT topic used for point writes or command publishes.", null),
                pointField("additionalConfig.qos", "select", "QoS", false, "",
                        List.of("0", "1", "2"), "MQTT quality of service level.", null),
                pointField("additionalConfig.retain", "boolean", "Retain", false, "",
                        List.of("true", "false"), "Whether writes or publishes should use MQTT retain.", null),
                pointField("additionalConfig.jsonPath", "string", "JSONPath", false, "",
                        Collections.emptyList(), "Path used to extract the target value from a JSON payload.", null),
                pointField("additionalConfig.payloadEncoding", "select", "Payload encoding", false, "",
                        List.of("JSON", "PLAIN_TEXT", "BASE64", "HEX"), "Decoder used for subscribed payloads.", null),
                pointField("additionalConfig.charset", "string", "Charset", false, "UTF-8",
                        Collections.emptyList(), "Charset used for text payload decoding.", null),
                pointField("additionalConfig.publishTemplate", "textarea", "Publish template", false, "",
                        Collections.emptyList(), "Template used to build MQTT payloads for point writes.", null)
        );
    }

    private List<ProtocolFieldConfig> iec104PointFields() {
        return List.of(
                pointField("additionalConfig.typeId", "number", "Type ID", false, "",
                        Collections.emptyList(), "IEC104 information object type identifier.", null),
                pointField("additionalConfig.iecTypeId", "number", "IEC type alias", false, "",
                        Collections.emptyList(), "Compatibility alias for existing IEC104 type-id configuration.", null),
                pointField("additionalConfig.writeAddress", "string", "Write address", false, "",
                        Collections.emptyList(), "Target write address used for control commands.", null),
                pointField("additionalConfig.writeCommonAddress", "number", "Write common address", false, "",
                        Collections.emptyList(), "Common address used for control commands.", null),
                pointField("additionalConfig.writeQl", "number", "Write quality", false, "",
                        Collections.emptyList(), "Quality descriptor used for control commands.", null),
                pointField("additionalConfig.writeSelect", "boolean", "Select before execute", false, "",
                        List.of("true", "false"), "Whether control commands should use select-before-execute.", null)
        );
    }

    private List<ProtocolFieldConfig> dlt645PointFields() {
        return List.of(
                pointField("additionalConfig.valueType", "select", "原始值类型", false, "BCD",
                        List.of("BCD", "DECIMAL", "UINT_LE", "INT_LE", "FLOAT_LE", "ASCII", "DATETIME", "HEX"),
                        "数据标识对应的数据区解析类型。", null),
                pointField("additionalConfig.dataFormat", "string", "BCD 数据格式", false, "",
                        Collections.emptyList(), "例如 XXXXXX.XX，用于确定小数位。", null),
                pointField("additionalConfig.valueIndex", "number", "值序号", false, "0",
                        Collections.emptyList(), "响应包含多个值时，从零开始选择目标值。", null)
        );
    }

    private List<ProtocolFieldConfig> iec101PointFields() {
        return List.of(
                pointField("additionalConfig.typeId", "number", "类型标识", false, "",
                        Collections.emptyList(), "IEC101 信息体类型标识。", null),
                pointField("additionalConfig.writeAddress", "string", "写入地址", false, "",
                        Collections.emptyList(), "遥控或设点命令使用的信息体地址。", null),
                pointField("additionalConfig.writeSelect", "boolean", "预置后执行", false, "false",
                        List.of("true", "false"), "启用后先发送选择命令，再发送执行命令。", null),
                pointField("additionalConfig.writeQualifier", "number", "命令限定词", false, "0",
                        Collections.emptyList(), "遥控或设点命令使用的限定词。", null)
        );
    }

    private List<ProtocolFieldConfig> coapPointFields() {
        return List.of(
                pointField("additionalConfig.path", "string", "Path", false, "",
                        Collections.emptyList(), "CoAP resource path when address is not a full URI.", null),
                pointField("additionalConfig.method", "select", "Method", false, "GET",
                        List.of("GET", "POST", "PUT", "DELETE"), "HTTP-like CoAP method used for the point.", null),
                pointField("additionalConfig.query", "string", "Query", false, "",
                        Collections.emptyList(), "Query string appended to the CoAP resource path.", null),
                pointField("additionalConfig.mediaType", "select", "Media type", false, "TEXT",
                        List.of("TEXT", "JSON", "CBOR", "OCTET"), "Payload media-type hint used for request/response decoding.", null),
                pointField("additionalConfig.observe", "boolean", "Observe", false, "",
                        List.of("true", "false"), "Whether this point should use CoAP Observe subscription mode.", null),
                pointField("additionalConfig.binary", "boolean", "Binary payload", false, "",
                        List.of("true", "false"), "Whether the payload should be treated as binary data.", null)
        );
    }

    private ProtocolFieldConfig pointField(String name,
                                           String type,
                                           String label,
                                           boolean required,
                                           String defaultValue,
                                           List<String> options,
                                           String description,
                                           String requiredWhen) {
        return ProtocolFieldConfig.builder()
                .name(name)
                .type(type)
                .label(label)
                .required(required)
                .defaultValue(defaultValue)
                .description(description)
                .group("protocol")
                .requiredWhen(requiredWhen)
                .storage(resolvePointStorage(name))
                .options(options == null ? Collections.emptyList() : options)
                .build();
    }

    private String resolvePointStorage(String name) {
        return name != null && name.startsWith("additionalConfig.") ? "extJson" : "topLevel";
    }

    private static List<String> appendOptions(List<String> base, String... values) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(base);
        if (values != null) {
            merged.addAll(List.of(values));
        }
        return List.copyOf(merged);
    }

    private void registerPrimary(ProtocolDescriptor descriptor) {
        descriptors.put(descriptor.code(), descriptor);
        for (String alias : descriptor.aliases()) {
            registerAlias(alias, descriptor.code(), null);
        }
    }

    private void registerAlias(String alias, String primaryCode, Consumer<DeviceConnection> customizer) {
        aliases.put(normalize(alias), new AliasDescriptor(primaryCode, customizer));
    }

    private ProtocolDescriptor descriptor(String code,
                                          String title,
                                          String description,
                                          List<String> aliases,
                                          Class<? extends com.wangbin.collector.core.collector.protocol.base.ProtocolCollector> collectorClass,
                                          String connectionType,
                                          Integer defaultPort,
                                          ProtocolAddressingMode addressingMode,
                                          boolean implemented,
                                          boolean writable,
                                          boolean subscribable,
                                          List<String> pointAddressHints,
                                          List<ProtocolFieldConfig> connectionFields) {
        return new ProtocolDescriptor(
                code,
                title,
                description,
                aliases,
                collectorClass,
                connectionType,
                defaultPort,
                addressingMode,
                resolveImplementationState(code, implemented),
                resolveWriteCapability(code, writable),
                resolveSubscriptionCapability(code, subscribable),
                resolveBrowseCapability(code),
                connectionFields,
                pointAddressHints
        );
    }

    private ProtocolCapabilityState resolveImplementationState(String protocol, boolean implemented) {
        if (!implemented) {
            return ProtocolCapabilityState.UNSUPPORTED;
        }
        return switch (protocol) {
            case "BACNET_SC", "CUSTOM_TCP", "CUSTOM_UDP", "OPC_UA_MILO", "DLT645_2007", "IEC101" ->
                    ProtocolCapabilityState.EXPERIMENTAL;
            default -> ProtocolCapabilityState.SUPPORTED;
        };
    }

    private ProtocolCapabilityState resolveWriteCapability(String protocol, boolean writable) {
        if (!writable) {
            return ProtocolCapabilityState.UNSUPPORTED;
        }
        return switch (protocol) {
            case "DLT645_2007", "IEC101" -> ProtocolCapabilityState.EXPERIMENTAL;
            default -> ProtocolCapabilityState.SUPPORTED;
        };
    }

    private ProtocolCapabilityState resolveSubscriptionCapability(String protocol, boolean subscribable) {
        if (!subscribable) {
            return ProtocolCapabilityState.UNSUPPORTED;
        }
        return switch (protocol) {
            case "IEC101" -> ProtocolCapabilityState.EXPERIMENTAL;
            case "SIEMENS_S7", "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "ADS", "KNXNET_IP",
                    "OPC_DA", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "SNMP", "COAP", "IEC104", "IEC61850" ->
                    ProtocolCapabilityState.RUNTIME_DEPENDENT;
            default -> ProtocolCapabilityState.SUPPORTED;
        };
    }

    private ProtocolCapabilityState resolveBrowseCapability(String protocol) {
        return switch (protocol) {
            case "SNMP" -> ProtocolCapabilityState.SUPPORTED;
            case "OPC_DA", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "IEC61850" ->
                    ProtocolCapabilityState.RUNTIME_DEPENDENT;
            default -> ProtocolCapabilityState.UNSUPPORTED;
        };
    }

    private List<ProtocolFieldConfig> customConnectionFields(boolean udp) {
        List<ProtocolFieldConfig> configured = new java.util.ArrayList<>();
        configured.add(field("host", "string", "设备主机", true, "127.0.0.1", null, "connection"));
        configured.add(field("port", "number", "设备端口", true, "", null, "connection"));
        configured.add(field("readRequestTemplate", "textarea", "读取请求模板", true, "", null, "request"));
        configured.add(field("writeRequestTemplate", "textarea", "写入请求模板", false, "", null, "request"));
        configured.add(field("requestEncoding", "select", "请求编码", true, "HEX",
                List.of("HEX", "TEXT", "BASE64"), "request"));
        configured.add(field("writeRequestEncoding", "select", "写入请求编码", false, "HEX",
                List.of("HEX", "TEXT", "BASE64"), "request"));
        configured.add(field("writeExpectResponse", "boolean", "写入等待响应", false, "true",
                List.of("true", "false"), "request"));
        configured.add(field("writeSuccessHex", "string", "写入成功响应前缀", false, "", null, "request"));
        configured.add(field("charset", "string", "字符集", false, "UTF-8", null, "request"));
        configured.add(field("readTimeout", "number", "读取超时（毫秒）", false, "5000", null, "advanced"));
        configured.add(field("bufferSize", "number", "接收缓冲区大小", false, "8192", null, "advanced"));
        if (!udp) {
            configured.add(field("frameMode", "select", "帧边界模式", true, "LENGTH_FIELD",
                    List.of("LENGTH_FIELD", "FIXED_LENGTH", "DELIMITER"), "protocol"));
            configured.add(field("fixedFrameLength", "number", "固定帧长度", false, "", null, "protocol"));
            configured.add(field("delimiterHex", "string", "分隔符十六进制", false, "0A", null, "protocol"));
            configured.add(field("lengthFieldOffset", "number", "长度字段偏移", false, "0", null, "protocol"));
            configured.add(field("lengthFieldLength", "select", "长度字段字节数", false, "4",
                    List.of("1", "2", "4", "8"), "protocol"));
            configured.add(field("lengthAdjustment", "number", "长度修正值", false, "0", null, "protocol"));
            configured.add(field("initialBytesToStrip", "number", "响应剥离字节数", false, "4", null, "protocol"));
            configured.add(field("prependLengthField", "boolean", "请求添加长度字段", false, "true",
                    List.of("true", "false"), "protocol"));
        }
        return List.copyOf(configured);
    }

    private List<ProtocolFieldConfig> opcUaFields() {
        return fields(
                field("url", "string", "Endpoint URL", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("endpointUrl", "string", "Endpoint URL alias", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("endpoint", "string", "Endpoint alias", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                field("port", "number", "Port", false, "4840", null, "connection"),
                field("discovery", "boolean", "Use discovery endpoint", false, "false",
                        List.of("true", "false"), "protocol"),
                field("authType", "select", "Authentication type", false, "ANONYMOUS",
                        List.of("ANONYMOUS", "USERNAME", "CERT"), "security"),
                field("securityPolicy", "select", "Security policy", false, "NONE",
                        List.of("NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256",
                                "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"),
                        "security"),
                field("messageSecurity", "select", "Message security", false, "NONE",
                        List.of("NONE", "SIGN", "SIGN_ENCRYPT"), "security"),
                field("securityMode", "select", "Security mode alias", false, "NONE",
                        List.of("NONE", "Sign", "SignAndEncrypt"), "security"),
                conditional("username", "string", "Username", false, "", null, "security", "authType=USERNAME"),
                field("password", "password", "Password", false, "", null, "security"),
                field("authParams", "object", "Authentication params alias", false, "{}", null, "security"),
                field("keyStoreFile", "string", "Client key store file", false, "", null, "security"),
                field("keyStoreType", "string", "Client key store type", false, "pkcs12", null, "security"),
                field("keyStorePassword", "password", "Client key store password", false, "", null, "security"),
                conditional("clientCertPath", "string", "Client certificate alias", false, "", null,
                        "security", "authType=CERT or securityPolicy!=NONE"),
                field("clientCertPassword", "password", "Client certificate password alias", false, "", null, "security"),
                field("trustStoreFile", "string", "Trust store file", false, "", null, "security"),
                field("trustStoreType", "string", "Trust store type", false, "pkcs12", null, "security"),
                field("trustStorePassword", "password", "Trust store password", false, "", null, "security"),
                field("serverCertificateFile", "string", "Pinned server certificate file", false, "", null, "security"),
                field("endpointHost", "string", "Endpoint host override", false, "", null, "advanced"),
                field("endpointPort", "number", "Endpoint port override", false, "", null, "advanced"),
                field("channelLifetime", "number", "Secure channel lifetime (ms)", false, "3600000", null, "advanced"),
                field("sessionTimeout", "number", "Session timeout (ms)", false, "120000", null, "advanced"),
                field("negotiationTimeout", "number", "Negotiation timeout (ms)", false, "5000", null, "advanced"),
                field("connectTimeoutMs", "number", "Connect timeout alias (ms)", false, "5000", null, "advanced"),
                field("connectTimeout", "number", "Connect timeout alias (ms)", false, "5000", null, "advanced"),
                field("requestTimeout", "number", "Request timeout (ms)", false, "5000", null, "advanced"),
                field("requestTimeoutMs", "number", "Request timeout alias (ms)", false, "5000", null, "advanced"),
                field("subscriptionInterval", "number", "Subscription interval (ms)", false, "1000", null, "advanced"),
                field("maxFieldsPerRequest", "number", "Max fields per request", false, "100", null, "advanced"),
                field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"));
    }

    private List<ProtocolFieldConfig> opcUaMiloFields() {
        return opcUaFields().stream()
                .filter(field -> !"plc4xConnectionString".equals(field.getName()))
                .toList();
    }

    private List<ProtocolFieldConfig> fields(ProtocolFieldConfig... fields) {
        return Arrays.asList(fields);
    }

    private ProtocolFieldConfig field(String name,
                                      String type,
                                      String label,
                                      boolean required,
                                      String defaultValue,
                                      List<String> options,
                                      String group) {
        return conditional(name, type, label, required, defaultValue, options, group, null, null);
    }

    private ProtocolFieldConfig field(String name,
                                      String type,
                                      String label,
                                      boolean required,
                                      String defaultValue,
                                      List<String> options,
                                      String group,
                                      String description) {
        return conditional(name, type, label, required, defaultValue, options, group, null, description);
    }

    private ProtocolFieldConfig conditional(String name,
                                            String type,
                                            String label,
                                            boolean required,
                                            String defaultValue,
                                            List<String> options,
                                            String group,
                                            String requiredWhen) {
        return conditional(name, type, label, required, defaultValue, options, group, requiredWhen, null);
    }

    private ProtocolFieldConfig conditional(String name,
                                            String type,
                                            String label,
                                            boolean required,
                                            String defaultValue,
                                            List<String> options,
                                            String group,
                                            String requiredWhen,
                                            String description) {
        return ProtocolFieldConfig.builder()
                .name(name)
                .type(type)
                .label(label)
                .required(required)
                .defaultValue(defaultValue)
                .description(description)
                .options(options == null ? Collections.emptyList() : options)
                .group(group)
                .requiredWhen(requiredWhen)
                .storage(resolveStorage(name))
                .build();
    }
    private String resolveStorage(String name) {
        return TOP_LEVEL_CONNECTION_FIELDS.contains(name) ? "topLevel" : "extJson";
    }

    private static void applyDefaultPort(DeviceConnection cfg, Integer defaultPort) {
        if (cfg != null && cfg.getPort() == null && defaultPort != null && defaultPort > 0) {
            cfg.setPort(defaultPort);
        }
    }

    private static void putExtIfAbsent(DeviceConnection cfg, String key, Object value) {
        if (cfg.getExtJson() == null) {
            cfg.setExtJson(new LinkedHashMap<>());
        }
        cfg.getExtJson().putIfAbsent(key, value);
    }

    private String normalize(String protocol) {
        return protocol.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    private record AliasDescriptor(String primaryCode, Consumer<DeviceConnection> customizer) {
    }
}

