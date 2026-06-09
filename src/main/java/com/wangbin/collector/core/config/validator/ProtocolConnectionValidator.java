package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
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
            case "SNMP" -> {
                requireHost(deviceInfo, connection, protocol);
                validateSnmp(deviceInfo, connection);
            }
            case "IEC104", "IEC61850" -> requireHost(deviceInfo, connection, protocol);
            case "OPC_UA", "OPCUA" -> validateOpcUa(deviceInfo, connection);
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

    private void validateOpcUa(DeviceInfo deviceInfo, DeviceConnection connection) {
        boolean hasEndpoint = hasText(connection.getUrl())
                || hasText(connection.getStringConfig("endpointUrl", null))
                || hasText(connection.getStringConfig("endpoint", null))
                || hasText(connection.getHost())
                || hasText(deviceInfo.getIpAddress());
        if (!hasEndpoint) {
            fail(deviceInfo, "OPC_UA requires url, endpointUrl, endpoint, or host");
        }

        String authType = connection.getStringConfig("authType", "ANONYMOUS").trim().toUpperCase();
        if ("USERNAME".equals(authType)) {
            String username = firstNonBlank(connection.getUsername(), authParam(connection, "username"));
            if (isBlank(username)) {
                fail(deviceInfo, "OPC_UA authType=USERNAME requires username");
            }
        } else if ("CERT".equals(authType)) {
            if (isBlank(connection.getStringConfig("clientCertPath", null))) {
                fail(deviceInfo, "OPC_UA authType=CERT requires clientCertPath");
            }
        }

        String securityPolicy = connection.getStringConfig("securityPolicy", "None");
        if (!isBlank(securityPolicy)
                && !"None".equalsIgnoreCase(securityPolicy)
                && !securityPolicy.endsWith("#None")
                && isBlank(connection.getStringConfig("clientCertPath", null))) {
            fail(deviceInfo, "OPC_UA secure securityPolicy requires clientCertPath");
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
            case "OPCUA" -> "OPC_UA";
            case "IEC_104" -> "IEC104";
            case "IEC_61850" -> "IEC61850";
            default -> normalized;
        };
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
