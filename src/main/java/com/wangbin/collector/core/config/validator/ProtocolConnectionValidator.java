package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.ads.util.AmsNetIdParser;
import org.springframework.stereotype.Component;

/**
 * Validates protocol-specific connection requirements before adapters are built.
 */
@Component
public class ProtocolConnectionValidator {

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
            case "SIEMENS_S7" -> requireHost(deviceInfo, connection, protocol);
            case "ETHERNET_IP" -> requireHost(deviceInfo, connection, protocol);
            case "ADS" -> validateAds(deviceInfo, connection);
            case "SNMP" -> {
                requireHost(deviceInfo, connection, protocol);
                validateSnmp(deviceInfo, connection);
            }
            case "IEC104", "IEC61850" -> requireHost(deviceInfo, connection, protocol);
            case "OPC_UA" -> validatePlc4xOpcUa(deviceInfo, connection, "OPC_UA");
            case "OPC_UA_PLC4X" -> validatePlc4xOpcUa(deviceInfo, connection, "OPC_UA_PLC4X");
            case "OPC_DA" -> validateOpcDa(deviceInfo, connection);
            case "MODBUS_RTU", "MODBUS_ASCII", "CUSTOM_TCP", "CUSTOM_UDP", "TCP" -> {
                // These protocols have usable defaults or protocol-specific validation later.
            }
            default -> {
                // Unknown protocol support is handled by CollectorFactory/ConnectionFactory.
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

    private void validatePlc4xOpcUa(DeviceInfo deviceInfo, DeviceConnection connection, String protocolLabel) {
        String connectionString = connection.getStringConfig("plc4xConnectionString", null);
        if (!hasOpcUaEndpoint(deviceInfo, connection)
                && isBlank(connectionString)) {
            fail(deviceInfo, protocolLabel + " requires plc4xConnectionString, url, endpointUrl, endpoint, or host");
        }

        if (hasText(connectionString)) {
            return;
        }

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
            fail(deviceInfo, protocolLabel + " generated config does not support trustAllServerCert; use trustStoreFile or plc4xConnectionString");
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
            case "EIP", "LOGIX", "AB_ETH" -> "ETHERNET_IP";
            case "AMS" -> "ADS";
            case "OPCUA" -> "OPC_UA";
            case "OPCUA_PLC4X" -> "OPC_UA_PLC4X";
            case "IEC_104" -> "IEC104";
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
