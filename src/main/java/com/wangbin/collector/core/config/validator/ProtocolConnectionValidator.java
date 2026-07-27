package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.ads.util.AmsNetIdParser;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validates protocol-specific connection requirements before adapters are built.
 */
@Component
public class ProtocolConnectionValidator {

    private static final Set<String> S7_CONTROLLER_TYPES = Set.of(
            "S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"
    );
    private static final Set<String> S7_DEVICE_GROUPS = Set.of(
            "PG_OR_PC", "OS", "OTHERS"
    );

    public void validate(DeviceInfo deviceInfo, DeviceConnection connection) {
        if (deviceInfo == null || isBlank(deviceInfo.getDeviceId())) {
            throw CollectorException.configException("deviceId is required", null, null);
        }
        if (connection == null) {
            throw CollectorException.configException("connection config is required", deviceInfo.getDeviceId(), null);
        }

        String protocol = resolveProtocol(deviceInfo, connection);
        if (isBlank(protocol)) {
            throw CollectorException.configException("protocolType or connectionType is required",
                    deviceInfo.getDeviceId(), null);
        }

        switch (canonicalize(protocol)) {
            case "HTTP", "MQTT", "WEBSOCKET", "COAP" -> requireUrlOrHostPort(deviceInfo, connection, protocol);
            case "MODBUS_TCP" -> requireHostPort(deviceInfo, connection, protocol);
            case "SIEMENS_S7" -> validateS7(deviceInfo, connection);
            case "MITSUBISHI_MC" -> validateMc(deviceInfo, connection);
            case "OMRON_FINS" -> validateOmronFins(deviceInfo, connection);
            case "BACNET_IP" -> validateBacnetIp(deviceInfo, connection);
            case "BACNET_MSTP" -> validateBacnetMstp(deviceInfo, connection);
            case "BACNET_SC" -> validateBacnetSc(deviceInfo, connection);
            case "ETHERNET_IP" -> requireHost(deviceInfo, connection, protocol);
            case "ADS" -> validateAds(deviceInfo, connection);
            case "KNXNET_IP" -> validateKnxNetIp(deviceInfo, connection);
            case "SNMP" -> {
                requireHost(deviceInfo, connection, protocol);
                validateSnmp(deviceInfo, connection);
            }
            case "IEC104", "IEC61850" -> requireHost(deviceInfo, connection, protocol);
            case "DLT645_2007" -> validateDlt645(deviceInfo, connection);
            case "IEC101" -> validateIec101(deviceInfo, connection);
            case "OPC_UA" -> validatePlc4xOpcUa(deviceInfo, connection, "OPC_UA");
            case "OPC_UA_PLC4X" -> validatePlc4xOpcUa(deviceInfo, connection, "OPC_UA_PLC4X");
            case "OPC_UA_MILO" -> validateMiloOpcUa(deviceInfo, connection);
            case "OPC_DA" -> validateOpcDa(deviceInfo, connection);
            case "CUSTOM_TCP", "CUSTOM_UDP" -> requireHostPort(deviceInfo, connection, protocol);
            case "MODBUS_RTU", "MODBUS_ASCII", "TCP" -> {
                // 这些协议具备可用的默认参数，或由后续协议流程执行专项校验。
            }
            default -> {
                // 未知协议由采集器工厂和连接工厂统一判定是否支持。
            }
        }
    }

    public boolean isValid(DeviceInfo deviceInfo, DeviceConnection connection) {
        try {
            validate(deviceInfo, connection);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void validateS7(DeviceInfo deviceInfo, DeviceConnection connection) {
        String connectionString = firstNonBlank(
                connection.getStringConfig("plc4xConnectionString", null),
                connection.getStringConfig("plc4x-connection-string", null));
        if (isBlank(connectionString)) {
            requireHost(deviceInfo, connection, "SIEMENS_S7");
        }

        Integer port = firstNonNull(connection.getPort(), deviceInfo.getPort());
        if (port != null && (port <= 0 || port > 65535)) {
            fail(deviceInfo, "SIEMENS_S7 port must be between 1 and 65535");
        }

        validateNonNegative(deviceInfo,
                firstNonNull(connection.getIntConfig("rack", null), connection.getIntConfig("remoteRack", null)),
                "SIEMENS_S7 rack");
        validateNonNegative(deviceInfo,
                firstNonNull(connection.getIntConfig("slot", null), connection.getIntConfig("remoteSlot", null)),
                "SIEMENS_S7 slot");
        validatePositive(deviceInfo, connection.getIntConfig("pduSize", null), "SIEMENS_S7 pduSize");
        validatePositive(deviceInfo, connection.getIntConfig("maxFieldsPerRequest", null),
                "SIEMENS_S7 maxFieldsPerRequest");
        validatePositive(deviceInfo, connection.getIntConfig("localTsap", null), "SIEMENS_S7 localTsap");
        validatePositive(deviceInfo, connection.getIntConfig("remoteTsap", null), "SIEMENS_S7 remoteTsap");
        validateNonNegative(deviceInfo, connection.getIntConfig("remoteRack2", null), "SIEMENS_S7 remoteRack2");
        validateNonNegative(deviceInfo, connection.getIntConfig("remoteSlot2", null), "SIEMENS_S7 remoteSlot2");
        validatePositive(deviceInfo, connection.getIntConfig("maxAmqCaller", null), "SIEMENS_S7 maxAmqCaller");
        validatePositive(deviceInfo, connection.getIntConfig("maxAmqCallee", null), "SIEMENS_S7 maxAmqCallee");
        validatePositive(deviceInfo, connection.getIntConfig("pingTime", null), "SIEMENS_S7 pingTime");
        validatePositive(deviceInfo, connection.getIntConfig("retryTime", null), "SIEMENS_S7 retryTime");
        validatePositive(deviceInfo, connection.getReadTimeout(), "SIEMENS_S7 readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "SIEMENS_S7 timeout");

        String controllerType = normalizeValue(firstNonBlank(
                connection.getStringConfig("controllerType", null),
                "S7_1200"));
        if (!S7_CONTROLLER_TYPES.contains(controllerType)) {
            fail(deviceInfo, "SIEMENS_S7 controllerType must be one of S7_300, S7_400, S7_1200, S7_1500, LOGO");
        }

        validateBooleanFlag(deviceInfo, connection.getProperty("subscriptionEnabled"),
                "SIEMENS_S7 subscriptionEnabled");
        validateS7DeviceGroup(deviceInfo, connection.getProperty("localDeviceGroup"), "localDeviceGroup");
        validateS7DeviceGroup(deviceInfo, connection.getProperty("remoteDeviceGroup"), "remoteDeviceGroup");
        validateS7DeviceGroup(deviceInfo, connection.getProperty("remoteDeviceGroup2"), "remoteDeviceGroup2");
    }

    private void validateMc(DeviceInfo deviceInfo, DeviceConnection connection) {
        requireHost(deviceInfo, connection, "MITSUBISHI_MC");

        Integer port = firstPositive(connection.getPort(), deviceInfo.getPort());
        if (port != null && (port <= 0 || port > 65535)) {
            fail(deviceInfo, "MITSUBISHI_MC port must be between 1 and 65535");
        }

        validateMcRange(deviceInfo, connection.getIntConfig("networkNo", null), 0, 255, "networkNo");
        validateMcRange(deviceInfo, connection.getIntConfig("pcNo", null), 0, 255, "pcNo");
        validateMcRange(deviceInfo, connection.getIntConfig("ioNo", null), 0, 65535, "ioNo");
        validateMcRange(deviceInfo, connection.getIntConfig("stationNo", null), 0, 255, "stationNo");
        validatePositive(deviceInfo, connection.getIntConfig("monitoringTimer", null), "MITSUBISHI_MC monitoringTimer");
        validatePositive(deviceInfo, connection.getIntConfig("maxRandomReadPoints", null), "MITSUBISHI_MC maxRandomReadPoints");
        validatePositive(deviceInfo, connection.getIntConfig("maxRandomWritePoints", null), "MITSUBISHI_MC maxRandomWritePoints");
        validatePositive(deviceInfo, connection.getIntConfig("maxWordsPerRequest", null), "MITSUBISHI_MC maxWordsPerRequest");
        validatePositive(deviceInfo, connection.getIntConfig("maxBitsPerRequest", null), "MITSUBISHI_MC maxBitsPerRequest");
        validatePositive(deviceInfo, connection.getReadTimeout(), "MITSUBISHI_MC readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "MITSUBISHI_MC timeout");
        validateMcFrameType(deviceInfo, connection.getStringConfig("frameType", null));
    }

    private void validateOmronFins(DeviceInfo deviceInfo, DeviceConnection connection) {
        requireHost(deviceInfo, connection, "OMRON_FINS");

        Integer port = firstPositive(connection.getPort(), deviceInfo.getPort());
        if (port != null && (port <= 0 || port > 65535)) {
            fail(deviceInfo, "OMRON_FINS port must be between 1 and 65535");
        }
        if (connection.getIntConfig("plcNode", null) == null) {
            fail(deviceInfo, "OMRON_FINS requires plcNode");
        }
        if (connection.getIntConfig("localNode", null) == null) {
            fail(deviceInfo, "OMRON_FINS requires localNode");
        }

        validateOmronFinsRange(deviceInfo, connection.getIntConfig("plcNetwork", null), 0, 255, "plcNetwork");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("plcNode", null), 0, 255, "plcNode");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("plcUnit", null), 0, 255, "plcUnit");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("localNetwork", null), 0, 255, "localNetwork");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("localNode", null), 0, 255, "localNode");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("localUnit", null), 0, 255, "localUnit");
        validateOmronFinsRange(deviceInfo, connection.getIntConfig("serviceIdSeed", null), 0, 255, "serviceIdSeed");
        validatePositive(deviceInfo, connection.getIntConfig("maxWordsPerRequest", null), "OMRON_FINS maxWordsPerRequest");
        validatePositive(deviceInfo, connection.getIntConfig("maxBitsPerRequest", null), "OMRON_FINS maxBitsPerRequest");
        validatePositive(deviceInfo, connection.getReadTimeout(), "OMRON_FINS readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "OMRON_FINS timeout");
        validateBooleanFlag(deviceInfo, connection.getProperty("batchReadEnabled"), "OMRON_FINS batchReadEnabled");
        validateFinsOrder(deviceInfo, connection.getStringConfig("byteOrder", null), "byteOrder");
        validateFinsOrder(deviceInfo, connection.getStringConfig("wordOrder", null), "wordOrder");
    }
    private void validateBacnetIp(DeviceInfo deviceInfo, DeviceConnection connection) {
        requireHost(deviceInfo, connection, "BACNET_IP");

        Integer port = firstPositive(connection.getPort(), deviceInfo.getPort());
        if (port != null && (port <= 0 || port > 65535)) {
            fail(deviceInfo, "BACNET_IP port must be between 1 and 65535");
        }

        Integer remoteDeviceInstance = firstPositive(
                connection.getIntConfig("remoteDeviceInstance", null),
                connection.getIntConfig("deviceInstance", null));
        if (remoteDeviceInstance == null) {
            fail(deviceInfo, "BACNET_IP requires remoteDeviceInstance");
        }

        validatePositive(deviceInfo, connection.getIntConfig("localDeviceInstance", null),
                "BACNET_IP localDeviceInstance");
        validatePositive(deviceInfo, connection.getIntConfig("localBindPort", null),
                "BACNET_IP localBindPort");
        validateNonNegative(deviceInfo, connection.getIntConfig("networkNumber", null),
                "BACNET_IP networkNumber");
        validatePositive(deviceInfo, connection.getIntConfig("apduTimeout", null),
                "BACNET_IP apduTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("segmentTimeout", null),
                "BACNET_IP segmentTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("retries", null),
                "BACNET_IP retries");
        validatePositive(deviceInfo, connection.getIntConfig("maxPropertiesPerRequest", null),
                "BACNET_IP maxPropertiesPerRequest");
        validatePositive(deviceInfo, connection.getIntConfig("foreignDeviceTtlSeconds", null),
                "BACNET_IP foreignDeviceTtlSeconds");
        validatePositive(deviceInfo, connection.getIntConfig("defaultCovLifetimeSeconds", null),
                "BACNET_IP defaultCovLifetimeSeconds");
        validatePositive(deviceInfo, connection.getReadTimeout(), "BACNET_IP readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "BACNET_IP timeout");

        validateBooleanFlag(deviceInfo, connection.getProperty("useWhoIsDiscovery"),
                "BACNET_IP useWhoIsDiscovery");
        validateBooleanFlag(deviceInfo, connection.getProperty("covEnabled"),
                "BACNET_IP covEnabled");
        validateBooleanFlag(deviceInfo, connection.getProperty("readPropertyMultipleEnabled"),
                "BACNET_IP readPropertyMultipleEnabled");
        validateBooleanFlag(deviceInfo, connection.getProperty("writePropertyMultipleEnabled"),
                "BACNET_IP writePropertyMultipleEnabled");
        validateBooleanFlag(deviceInfo, connection.getProperty("resubscribeOnReconnect"),
                "BACNET_IP resubscribeOnReconnect");

        String bbmdHost = firstNonBlank(
                connection.getStringConfig("bbmdHost", null),
                connection.getStringConfig("bbmd-host", null));
        if (hasText(bbmdHost)) {
            Integer bbmdPort = connection.getIntConfig("bbmdPort", null);
            if (bbmdPort == null || bbmdPort <= 0 || bbmdPort > 65535) {
                fail(deviceInfo, "BACNET_IP bbmdHost requires valid bbmdPort");
            }
            Integer ttl = connection.getIntConfig("foreignDeviceTtlSeconds", null);
            if (ttl == null || ttl <= 0) {
                fail(deviceInfo, "BACNET_IP bbmdHost requires foreignDeviceTtlSeconds");
            }
        }

        Boolean covEnabled = connection.getBoolConfig("covEnabled", false);
        if (Boolean.TRUE.equals(covEnabled)
                && connection.getIntConfig("localBindPort", null) == null) {
            fail(deviceInfo, "BACNET_IP covEnabled=true requires localBindPort");
        }
    }


    private void validateBacnetMstp(DeviceInfo deviceInfo, DeviceConnection connection) {
        String serialPort = firstNonBlank(
                connection.getStringConfig("serialPort", null),
                connection.getHost());
        if (isBlank(serialPort)) {
            fail(deviceInfo, "BACNET_MSTP requires serialPort");
        }

        Integer localMac = firstNonNull(
                connection.getIntConfig("localMacAddress", null),
                connection.getIntConfig("macAddress", null));
        if (localMac == null || localMac < 0 || localMac > 254) {
            fail(deviceInfo, "BACNET_MSTP localMacAddress/macAddress must be between 0 and 254");
        }

        Integer remoteMac = firstNonNull(
                connection.getIntConfig("remoteMacAddress", null),
                connection.getIntConfig("targetMacAddress", null));
        if (remoteMac == null || remoteMac < 0 || remoteMac > 254) {
            fail(deviceInfo, "BACNET_MSTP requires remoteMacAddress between 0 and 254");
        }

        Integer remoteDeviceInstance = connection.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            fail(deviceInfo, "BACNET_MSTP requires remoteDeviceInstance");
        }

        validatePositive(deviceInfo, connection.getIntConfig("baudRate", null), "BACNET_MSTP baudRate");
        validatePositive(deviceInfo, connection.getIntConfig("apduTimeout", null), "BACNET_MSTP apduTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("segmentTimeout", null), "BACNET_MSTP segmentTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("retries", null), "BACNET_MSTP retries");
        validatePositive(deviceInfo, connection.getIntConfig("maxInfoFrames", null), "BACNET_MSTP maxInfoFrames");
        validatePositive(deviceInfo, connection.getIntConfig("tokenClaimTimeoutMs", null), "BACNET_MSTP tokenClaimTimeoutMs");
        validatePositive(deviceInfo, connection.getIntConfig("replyTimeoutMs", null), "BACNET_MSTP replyTimeoutMs");
        validatePositive(deviceInfo, connection.getIntConfig("pollForMasterTimeoutMs", null), "BACNET_MSTP pollForMasterTimeoutMs");
        validatePositive(deviceInfo, connection.getReadTimeout(), "BACNET_MSTP readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "BACNET_MSTP timeout");

        Integer dataBits = connection.getIntConfig("dataBits", null);
        if (dataBits != null && (dataBits < 5 || dataBits > 8)) {
            fail(deviceInfo, "BACNET_MSTP dataBits must be between 5 and 8");
        }
        Integer stopBits = connection.getIntConfig("stopBits", null);
        if (stopBits != null && stopBits != 1 && stopBits != 2) {
            fail(deviceInfo, "BACNET_MSTP stopBits must be 1 or 2");
        }
        Integer maxMaster = connection.getIntConfig("maxMaster", null);
        if (maxMaster != null && (maxMaster < 0 || maxMaster > 127)) {
            fail(deviceInfo, "BACNET_MSTP maxMaster must be between 0 and 127");
        }
        Integer nextStationMac = connection.getIntConfig("nextStationMac", null);
        if (nextStationMac != null && (nextStationMac < 0 || nextStationMac > 127)) {
            fail(deviceInfo, "BACNET_MSTP nextStationMac must be between 0 and 127");
        }
        String parity = connection.getStringConfig("parity", null);
        if (hasText(parity)) {
            String normalized = parity.trim().toLowerCase(Locale.ROOT);
            if (!("none".equals(normalized) || "odd".equals(normalized) || "even".equals(normalized))) {
                fail(deviceInfo, "BACNET_MSTP parity must be none, odd, or even");
            }
        }
        validateBooleanFlag(deviceInfo, connection.getProperty("remoteIsMaster"), "BACNET_MSTP remoteIsMaster");
    }

    private void validateDlt645(DeviceInfo deviceInfo, DeviceConnection connection) {
        validateSerialConnection(deviceInfo, connection, "DL/T 645");
        String meterAddress = connection.getStringConfig("meterAddress", null);
        if (isBlank(meterAddress)
                || !meterAddress.replace(" ", "").replace("-", "").matches("(?i)[0-9a-f]{12}")) {
            fail(deviceInfo, "DL/T 645 电表通信地址必须是 12 位十六进制字符");
        }
        validateNonNegative(deviceInfo, connection.getIntConfig("retryCount", null), "DL/T 645 重试次数");
        validateNonNegative(deviceInfo, connection.getIntConfig("wakeupByteCount", null), "DL/T 645 唤醒字节数");
        validateNonNegative(deviceInfo, connection.getIntConfig("interFrameDelayMs", null), "DL/T 645 帧间隔");

        if (Boolean.TRUE.equals(connection.getBoolConfig("writeEnabled", false))) {
            validateHexCredential(deviceInfo, connection.getStringConfig("writePasswordHex", null), "写入密码");
            validateHexCredential(deviceInfo, connection.getStringConfig("operatorCodeHex", null), "操作者代码");
        }
    }

    private void validateIec101(DeviceInfo deviceInfo, DeviceConnection connection) {
        validateSerialConnection(deviceInfo, connection, "IEC101");
        String linkMode = connection.getStringConfig("linkMode", "UNBALANCED");
        if (!"UNBALANCED".equalsIgnoreCase(linkMode)) {
            fail(deviceInfo, "IEC101 当前仅支持非平衡式控制站模式");
        }
        validateSizedAddress(deviceInfo, connection.getIntConfig("linkAddress", 1),
                connection.getIntConfig("linkAddressSize", 1), "IEC101 链路地址");
        validateSizedAddress(deviceInfo, connection.getIntConfig("commonAddress", 1),
                connection.getIntConfig("commonAddressSize", 2), "IEC101 公共地址");
        validateRange(deviceInfo, connection.getIntConfig("causeOfTransmissionSize", 2), 1, 2,
                "IEC101 传送原因长度");
        validateRange(deviceInfo, connection.getIntConfig("informationObjectAddressSize", 3), 1, 3,
                "IEC101 信息体地址长度");
        validateNonNegative(deviceInfo, connection.getIntConfig("retryCount", null), "IEC101 重试次数");
        validatePositive(deviceInfo, connection.getIntConfig("class1PollIntervalMs", null), "IEC101 一级数据轮询周期");
        validatePositive(deviceInfo, connection.getIntConfig("class2PollIntervalMs", null), "IEC101 二级数据轮询周期");
    }

    private void validateSerialConnection(DeviceInfo deviceInfo,
                                          DeviceConnection connection,
                                          String protocolName) {
        if (isBlank(connection.getStringConfig("serialPort", null))) {
            fail(deviceInfo, protocolName + " 必须配置串口名称");
        }
        validatePositive(deviceInfo, connection.getIntConfig("baudRate", null), protocolName + " 波特率");
        validateRange(deviceInfo, connection.getIntConfig("dataBits", 8), 5, 8, protocolName + " 数据位");
        validateRange(deviceInfo, connection.getIntConfig("stopBits", 1), 1, 2, protocolName + " 停止位");
        String parity = connection.getStringConfig("parity", "EVEN").toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "EVEN", "ODD").contains(parity)) {
            fail(deviceInfo, protocolName + " 校验位只允许 NONE、EVEN 或 ODD");
        }
        validatePositive(deviceInfo, connection.getIntConfig("readTimeout", null), protocolName + " 读取超时");
        validatePositive(deviceInfo, connection.getIntConfig("writeTimeout", null), protocolName + " 写入超时");
    }

    private void validateHexCredential(DeviceInfo deviceInfo, String value, String fieldName) {
        if (isBlank(value) || !value.replace(" ", "").matches("(?i)[0-9a-f]{8}")) {
            fail(deviceInfo, "DL/T 645 " + fieldName + "必须是 4 字节十六进制字符");
        }
    }

    private void validateSizedAddress(DeviceInfo deviceInfo, Integer address, Integer size, String fieldName) {
        validateRange(deviceInfo, size, 1, 2, fieldName + "长度");
        int maximum = size != null && size == 1 ? 0xFF : 0xFFFF;
        if (address == null || address < 0 || address > maximum) {
            fail(deviceInfo, fieldName + "超出当前地址长度允许范围");
        }
    }

    private void validateRange(DeviceInfo deviceInfo, Integer value, int min, int max, String fieldName) {
        if (value == null || value < min || value > max) {
            fail(deviceInfo, fieldName + "必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private void validateBacnetSc(DeviceInfo deviceInfo, DeviceConnection connection) {
        requireUrlOrHostPort(deviceInfo, connection, "BACNET_SC");

        String url = firstNonBlank(connection.getUrl(), connection.getStringConfig("endpointUrl", null));
        if (hasText(url) && !url.toLowerCase().startsWith("wss://")) {
            fail(deviceInfo, "BACNET_SC requires a wss:// secure WebSocket URL");
        }
        if (Boolean.TRUE.equals(connection.getBoolConfig("trustAllServerCert", false))) {
            fail(deviceInfo, "BACNET_SC does not allow trustAllServerCert");
        }
        if (isBlank(connection.getStringConfig("keyStoreFile", null))) {
            fail(deviceInfo, "BACNET_SC requires keyStoreFile for mutual TLS");
        }
        if (isBlank(connection.getStringConfig("trustStoreFile", null))) {
            fail(deviceInfo, "BACNET_SC requires trustStoreFile for server certificate validation");
        }

        Integer remoteDeviceInstance = connection.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            fail(deviceInfo, "BACNET_SC requires remoteDeviceInstance");
        }

        Integer port = firstPositive(connection.getPort(), deviceInfo.getPort());
        if (port != null && (port <= 0 || port > 65535)) {
            fail(deviceInfo, "BACNET_SC port must be between 1 and 65535");
        }

        validatePositive(deviceInfo, connection.getIntConfig("apduTimeout", null), "BACNET_SC apduTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("segmentTimeout", null), "BACNET_SC segmentTimeout");
        validatePositive(deviceInfo, connection.getIntConfig("retries", null), "BACNET_SC retries");
        validatePositive(deviceInfo, connection.getReadTimeout(), "BACNET_SC readTimeout");
        validatePositive(deviceInfo, connection.getTimeout(), "BACNET_SC timeout");
        validatePositive(deviceInfo, connection.getConnectTimeout(), "BACNET_SC connectTimeout");
    }
    private void validatePlc4xOpcUa(DeviceInfo deviceInfo, DeviceConnection connection, String protocolLabel) {
        String connectionString = connection.getStringConfig("plc4xConnectionString", null);
        if (!hasOpcUaEndpoint(deviceInfo, connection)
                && isBlank(connectionString)) {
            fail(deviceInfo, protocolLabel + " requires plc4xConnectionString, url, endpointUrl, endpoint, or host");
        }

        if (hasText(connectionString)) {
            return;
        }

        validateOpcUaSecurity(deviceInfo, connection, protocolLabel, true);
    }

    private void validateMiloOpcUa(DeviceInfo deviceInfo, DeviceConnection connection) {
        if (!hasOpcUaEndpoint(deviceInfo, connection)) {
            fail(deviceInfo, "OPC_UA_MILO requires url, endpointUrl, endpoint, or host");
        }
        validateOpcUaSecurity(deviceInfo, connection, "OPC_UA_MILO", false);
    }

    private void validateOpcUaSecurity(DeviceInfo deviceInfo,
                                       DeviceConnection connection,
                                       String protocolLabel,
                                       boolean allowPlc4xConnectionString) {
        String authType = firstNonBlank(connection.getStringConfig("authType", null), "ANONYMOUS")
                .trim()
                .toUpperCase();
        String username = firstNonBlank(
                connection.getStringConfig("username", null),
                connection.getUsername(),
                authParam(connection, "username"));
        String password = firstNonBlank(
                connection.getStringConfig("password", null),
                connection.getPassword(),
                authParam(connection, "password"));
        if ("USERNAME".equals(authType)) {
            if (isBlank(username)) {
                fail(deviceInfo, protocolLabel + " authType=USERNAME requires username");
            }
            if (isBlank(password)) {
                fail(deviceInfo, protocolLabel + " authType=USERNAME requires password");
            }
        } else if (!isBlank(username) && isBlank(password)) {
            fail(deviceInfo, protocolLabel + " username requires password");
        }

        String keyStoreFile = firstNonBlank(
                connection.getStringConfig("keyStoreFile", null),
                connection.getStringConfig("clientCertPath", null));
        if ("CERT".equals(authType) && isBlank(keyStoreFile)) {
            fail(deviceInfo, protocolLabel + " authType=CERT requires keyStoreFile or clientCertPath");
        }

        String securityPolicy = firstNonBlank(
                connection.getStringConfig("securityPolicy", null),
                "NONE");
        if (!isBlank(securityPolicy)
                && !"NONE".equalsIgnoreCase(securityPolicy)
                && !securityPolicy.endsWith("#None")
                && isBlank(keyStoreFile)) {
            fail(deviceInfo, protocolLabel + " secure securityPolicy requires keyStoreFile or clientCertPath");
        }

        if (Boolean.TRUE.equals(connection.getBoolConfig("trustAllServerCert", false))) {
            String supportedTrustConfig = allowPlc4xConnectionString
                    ? "trustStoreFile or plc4xConnectionString"
                    : "trustStoreFile";
            fail(deviceInfo, protocolLabel
                    + " generated config does not support trustAllServerCert; use "
                    + supportedTrustConfig);
        }
    }

    private void validateOpcDa(DeviceInfo deviceInfo, DeviceConnection connection) {
        String bridgeMode = firstNonBlank(
                connection.getStringConfig("bridgeMode", null),
                connection.getStringConfig("bridge-mode", null),
                connection.getStringConfig("opcDaBridgeMode", null),
                "INMEMORY");
        if ("HTTP".equalsIgnoreCase(bridgeMode)) {
            String bridgeUrl = firstNonBlank(
                    connection.getStringConfig("bridgeBaseUrl", null),
                    connection.getStringConfig("bridge-url", null),
                    connection.getStringConfig("opcDaBridgeUrl", null),
                    connection.getUrl());
            if (isBlank(bridgeUrl)) {
                fail(deviceInfo, "OPC_DA bridgeMode=HTTP requires bridgeBaseUrl or url");
            }
        }
    }

    private void requireUrlOrHostPort(DeviceInfo deviceInfo,
                                      DeviceConnection connection,
                                      String protocol) {
        if (hasText(connection.getUrl())) {
            return;
        }
        if ("MQTT".equals(canonicalize(protocol)) && hasText(connection.getStringConfig("brokerUrl", null))) {
            return;
        }
        boolean hasHost = hasText(connection.getHost()) || hasText(deviceInfo.getIpAddress());
        boolean hasPort = firstPositive(connection.getPort(), deviceInfo.getPort()) != null;
        if (!hasHost || !hasPort) {
            fail(deviceInfo, protocol + " requires url or host+port");
        }
    }

    private void requireHostPort(DeviceInfo deviceInfo, DeviceConnection connection, String protocol) {
        boolean hasHost = hasText(connection.getHost()) || hasText(deviceInfo.getIpAddress());
        boolean hasPort = firstPositive(connection.getPort(), deviceInfo.getPort()) != null;
        if (!hasHost || !hasPort) {
            fail(deviceInfo, protocol + " requires host+port");
        }
    }

    private void requireHost(DeviceInfo deviceInfo, DeviceConnection connection, String protocol) {
        if (!hasText(connection.getHost()) && !hasText(deviceInfo.getIpAddress())) {
            fail(deviceInfo, protocol + " requires host or device ipAddress");
        }
    }

    private void validateSnmp(DeviceInfo deviceInfo, DeviceConnection connection) {
        String version = connection.getStringConfig("snmpVersion", "2c").trim().toLowerCase();
        if (!("3".equals(version) || "v3".equals(version))) {
            if (isBlank(connection.getStringConfig("community", "public"))) {
                fail(deviceInfo, "SNMP v1/v2c requires community");
            }
            return;
        }

        if (isBlank(connection.getStringConfig("snmpSecurityName", null))) {
            fail(deviceInfo, "SNMPv3 requires snmpSecurityName");
        }

        String securityLevel = connection.getStringConfig("snmpSecurityLevel", "authPriv").trim();
        if ("authNoPriv".equalsIgnoreCase(securityLevel) || "authPriv".equalsIgnoreCase(securityLevel)) {
            if (isBlank(connection.getStringConfig("snmpAuthPassword", null))) {
                fail(deviceInfo, "SNMPv3 " + securityLevel + " requires snmpAuthPassword");
            }
        }
        if ("authPriv".equalsIgnoreCase(securityLevel)
                && isBlank(connection.getStringConfig("snmpPrivPassword", null))) {
            fail(deviceInfo, "SNMPv3 authPriv requires snmpPrivPassword");
        }
    }

    private void validateAds(DeviceInfo deviceInfo, DeviceConnection connection) {
        requireHost(deviceInfo, connection, "ADS");

        String targetAmsNetId = firstNonBlank(
                connection.getStringConfig("targetAmsNetId", null),
                connection.getStringConfig("target-ams-net-id", null));
        if (!AmsNetIdParser.isValid(targetAmsNetId)) {
            fail(deviceInfo, "ADS requires valid targetAmsNetId");
        }

        Integer targetAmsPort = firstPositive(
                connection.getIntConfig("targetAmsPort", null),
                connection.getIntConfig("target-ams-port", null));
        if (targetAmsPort == null) {
            fail(deviceInfo, "ADS requires targetAmsPort");
        }

        String sourceAmsNetId = firstNonBlank(
                connection.getStringConfig("sourceAmsNetId", null),
                connection.getStringConfig("source-ams-net-id", null));
        if (!AmsNetIdParser.isValid(sourceAmsNetId)) {
            fail(deviceInfo, "ADS requires valid sourceAmsNetId");
        }

        Integer sourceAmsPort = firstPositive(
                connection.getIntConfig("sourceAmsPort", null),
                connection.getIntConfig("source-ams-port", null));
        if (sourceAmsPort == null) {
            fail(deviceInfo, "ADS requires sourceAmsPort");
        }
    }

    private void validateKnxNetIp(DeviceInfo deviceInfo, DeviceConnection connection) {
        String connectionString = firstNonBlank(
                connection.getStringConfig("plc4xConnectionString", null),
                connection.getStringConfig("plc4x-connection-string", null));
        if (isBlank(connectionString)
                && !hasText(connection.getHost())
                && !hasText(deviceInfo.getIpAddress())) {
            fail(deviceInfo, "KNXNET_IP requires plc4xConnectionString, host, or device ipAddress");
        }

        Integer groupAddressNumLevels = firstNonNull(
                connection.getIntConfig("groupAddressNumLevels", null),
                connection.getIntConfig("group-address-num-levels", null));
        if (groupAddressNumLevels != null
                && groupAddressNumLevels != 1
                && groupAddressNumLevels != 2
                && groupAddressNumLevels != 3) {
            fail(deviceInfo, "KNXNET_IP groupAddressNumLevels must be 1, 2, or 3");
        }

        String knxConnectionType = firstNonBlank(
                connection.getStringConfig("knxConnectionType", null),
                connection.getStringConfig("connection-type", null),
                connection.getStringConfig("knxnetIpConnectionType", null));
        if (hasText(knxConnectionType)) {
            String normalized = knxConnectionType.trim().toUpperCase();
            if (!("LINK_LAYER".equals(normalized)
                    || "RAW".equals(normalized)
                    || "BUSMONITOR".equals(normalized))) {
                fail(deviceInfo, "KNXNET_IP knxConnectionType must be LINK_LAYER, RAW, or BUSMONITOR");
            }
        }
    }

    private String resolveProtocol(DeviceInfo deviceInfo, DeviceConnection connection) {
        return firstNonBlank(
                deviceInfo.getProtocolType(),
                deviceInfo.getConnectionType(),
                connection.getConnectionType());
    }

    private String canonicalize(String protocol) {
        if (protocol == null) {
            return "";
        }
        String normalized = protocol.trim().toUpperCase().replace("-", "_");
        return switch (normalized) {
            case "HTTPS" -> "HTTP";
            case "WEBSOCKET_SSL" -> "WEBSOCKET";
            case "MQTT_SSL" -> "MQTT";
            case "COAP_SSL" -> "COAP";
            case "SNMP_V1", "SNMP_V2C", "SNMP_V3" -> "SNMP";
            case "MODBUS_ASCII" -> "MODBUS_RTU";
            case "S7" -> "SIEMENS_S7";
            case "MC", "MELSEC_MC" -> "MITSUBISHI_MC";
            case "FINS", "OMRONFINS" -> "OMRON_FINS";
            case "BACNET", "BACNETIP", "BACNET/IP" -> "BACNET_IP";
            case "BACNETMSTP", "BACNET_MSTP", "BACNET_MS_TP", "BACNET-MSTP", "BACNET MSTP" -> "BACNET_MSTP";
            case "BACNETSC", "BACNET_SC", "BACNET/SC", "BACNET-SC" -> "BACNET_SC";
            case "EIP", "LOGIX", "AB_ETH" -> "ETHERNET_IP";
            case "AMS" -> "ADS";
            case "KNX", "KNXNETIP", "KNX_NET_IP", "KNXNET/IP" -> "KNXNET_IP";
            case "OPCUA" -> "OPC_UA";
            case "OPCUA_PLC4X" -> "OPC_UA_PLC4X";
            case "OPCUA_MILO" -> "OPC_UA_MILO";
            case "IEC_104" -> "IEC104";
            case "DLT645", "DL_T_645", "DLT_645_2007" -> "DLT645_2007";
            case "IEC_101", "IEC60870_5_101" -> "IEC101";
            case "IEC_61850" -> "IEC61850";
            default -> normalized;
        };
    }

    private boolean hasOpcUaEndpoint(DeviceInfo deviceInfo, DeviceConnection connection) {
        return hasText(connection.getUrl())
                || hasText(connection.getStringConfig("endpointUrl", null))
                || hasText(connection.getStringConfig("endpoint", null))
                || hasText(connection.getHost())
                || hasText(deviceInfo.getIpAddress());
    }

    private Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String authParam(DeviceConnection connection, String key) {
        if (connection.getAuthParams() == null) {
            return null;
        }
        return connection.getAuthParams().get(key);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void validateMcRange(DeviceInfo deviceInfo,
                                 Integer value,
                                 int min,
                                 int max,
                                 String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            fail(deviceInfo, "MITSUBISHI_MC " + fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateOmronFinsRange(DeviceInfo deviceInfo,
                                        Integer value,
                                        int min,
                                        int max,
                                        String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            fail(deviceInfo, "OMRON_FINS " + fieldName + " must be between " + min + " and " + max);
        }
    }

    private void validateFinsOrder(DeviceInfo deviceInfo, String value, String fieldName) {
        if (isBlank(value)) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("BIG_ENDIAN", "LITTLE_ENDIAN", "BIG", "LITTLE", "BE", "LE").contains(normalized)) {
            fail(deviceInfo, "OMRON_FINS " + fieldName + " must be BIG_ENDIAN or LITTLE_ENDIAN");
        }
    }
    private void validateMcFrameType(DeviceInfo deviceInfo, String value) {
        if (isBlank(value)) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("3E_BINARY", "3E_ASCII", "4E_BINARY").contains(normalized)) {
            fail(deviceInfo, "MITSUBISHI_MC frameType must be one of 3E_BINARY, 3E_ASCII, 4E_BINARY");
        }
    }

    private void validatePositive(DeviceInfo deviceInfo, Integer value, String fieldName) {
        if (value != null && value <= 0) {
            fail(deviceInfo, fieldName + " must be greater than 0");
        }
    }

    private void validateNonNegative(DeviceInfo deviceInfo, Integer value, String fieldName) {
        if (value != null && value < 0) {
            fail(deviceInfo, fieldName + " must be greater than or equal to 0");
        }
    }

    private void validateBooleanFlag(DeviceInfo deviceInfo, Object value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean) {
            return;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            fail(deviceInfo, fieldName + " must be true or false");
        }
    }

    private void validateS7DeviceGroup(DeviceInfo deviceInfo, Object value, String fieldName) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        String normalized = normalizeValue(value.toString());
        if (!S7_DEVICE_GROUPS.contains(normalized)) {
            fail(deviceInfo, "SIEMENS_S7 " + fieldName + " must be PG_OR_PC, OS, or OTHERS");
        }
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isBlank(String value) {
        return !hasText(value);
    }

    private void fail(DeviceInfo deviceInfo, String message) {
        throw CollectorException.configException(message, deviceInfo.getDeviceId(), null);
    }
}

