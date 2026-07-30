package com.wangbin.collector.core.collector.protocol.snmp.base;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpAddress;
import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpDataType;
import com.wangbin.collector.core.collector.protocol.snmp.util.SnmpUtils;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SNMP 公共能力抽象。
 */
@Slf4j
public abstract class AbstractSnmpCollector extends ConnectionBackedCollector {

    protected SnmpConnectionAdapter snmpConnection;

    protected CollectorProperties.SnmpConfig snmpConfig;

    protected String host;
    protected int port = 161;
    protected String community = "public";
    protected int timeout = 5000;
    protected int retries = 1;
    protected int version = SnmpConstants.version2c;
    protected String versionText = "2c";
    protected String securityLevelText = "authPriv";
    protected String securityName;
    protected String authProtocol = "SHA";
    protected String authPassword;
    protected String privProtocol = "AES128";
    protected String privPassword;
    protected String contextName;
    protected OctetString contextNameOctet;
    protected OctetString contextEngineIdOctet;

    protected DeviceConnection initSnmpConfig(DeviceInfo deviceInfo) {
        this.snmpConfig = collectorProperties != null
                ? collectorProperties.getSnmp()
                : new CollectorProperties.SnmpConfig();

        DeviceConnection connection = requireConnectionConfig();

        host = connection.getHost();
        connection.setHost(host);

        port = connection.getPort();
        connection.setPort(port);

        community = connection.getStringConfig("community",
                snmpConfig != null ? snmpConfig.getCommunity() : community);
        timeout = connection.getReadTimeout();
        connection.setReadTimeout(timeout);
        connection.setWriteTimeout(connection.getWriteTimeout());

        retries = connection.getIntConfig("snmpRetries",
                snmpConfig != null ? snmpConfig.getRetries() : this.retries);
        versionText = connection.getStringConfig("snmpVersion",
                snmpConfig != null ? snmpConfig.getVersion() : "2c").trim();

        switch (versionText) {
            case "1":
                version = SnmpConstants.version1;
                break;
            case "3":
                version = SnmpConstants.version3;
                break;
            case "2c":
            default:
                version = SnmpConstants.version2c;
        }

        securityName = connection.getStringConfig("snmpSecurityName",
                snmpConfig != null ? snmpConfig.getSecurityName() : null);
        securityLevelText = connection.getStringConfig("snmpSecurityLevel",
                snmpConfig != null ? snmpConfig.getSecurityLevel() : "authPriv");
        authProtocol = connection.getStringConfig("snmpAuthProtocol",
                snmpConfig != null ? snmpConfig.getAuthProtocol() : "SHA");
        authPassword = connection.getStringConfig("snmpAuthPassword",
                snmpConfig != null ? snmpConfig.getAuthPassword() : null);
        privProtocol = connection.getStringConfig("snmpPrivProtocol",
                snmpConfig != null ? snmpConfig.getPrivProtocol() : "AES128");
        privPassword = connection.getStringConfig("snmpPrivPassword",
                snmpConfig != null ? snmpConfig.getPrivPassword() : null);
        contextName = connection.getStringConfig("snmpContextName",
                snmpConfig != null ? snmpConfig.getContextName() : null);
        if (StringUtils.hasText(contextName)) {
            contextNameOctet = new OctetString(contextName);
        } else {
            contextNameOctet = null;
        }
        String contextEngineId = connection.getStringConfig("snmpContextEngineId",
                snmpConfig != null ? snmpConfig.getContextEngineId() : null);
        contextEngineIdOctet = SnmpUtils.parseContextEngineId(contextEngineId);

        return connection;
    }

    protected void initSnmpConnection(DeviceConnection connectionConfig) throws Exception {
        this.snmpConnection = createAndConnectAdapter(connectionConfig, SnmpConnectionAdapter.class, "SNMP");
    }

    protected void closeSnmpConnection() {
        removeManagedConnection("SNMP");
        snmpConnection = null;
    }


    protected Map<String, Variable> performGet(List<SnmpAddress> addresses) throws IOException {
        try {
            return executeSnmp((snmp, target) -> {
                Map<String, Variable> values = new LinkedHashMap<>();
                for (List<SnmpAddress> chunk : SnmpUtils.partition(addresses, 10)) {
                    PDU pdu = createPdu(PDU.GET, chunk);
                    ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
                    PDU response = validateResponse(event);
                    for (int i = 0; i < response.size(); i++) {
                        VariableBinding binding = response.get(i);
                        values.put(binding.getOid().toDottedString(), binding.getVariable());
                    }
                }
                return values;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SNMP GET 执行失败", e);
        }
    }

    protected void performSet(Map<SnmpAddress, Object> values) throws IOException {
        if (values.isEmpty()) {
            return;
        }
        try {
            executeSnmp((snmp, target) -> {
                List<VariableBinding> bindings = new java.util.ArrayList<>();
                for (Map.Entry<SnmpAddress, Object> entry : values.entrySet()) {
                    SnmpAddress address = entry.getKey();
                    Variable variable = SnmpUtils.toVariable(entry.getValue(), address.getDataType());
                    bindings.add(new VariableBinding(new OID(address.getOid()), variable));
                }
                for (List<VariableBinding> chunk : SnmpUtils.partitionBindings(bindings, 10)) {
                    PDU pdu = newPduInstance();
                    chunk.forEach(pdu::add);
                    pdu.setType(PDU.SET);
                    ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
                    validateResponse(event);
                }
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SNMP SET 执行失败", e);
        }
    }

    protected List<VariableBinding> performWalk(String rootOid, int maxNodes) throws IOException {
        try {
            return executeSnmp((snmp, target) -> {
                List<VariableBinding> nodes = new java.util.ArrayList<>();
                OID currentRoot = new OID(rootOid);
                OID current = currentRoot;
                int count = 0;
                while (count < maxNodes) {
                    PDU pdu = newPduInstance();
                    pdu.add(new VariableBinding(current));
                    pdu.setType(PDU.GETNEXT);
                    ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
                    PDU response = validateResponse(event);
                    if (response.size() == 0) {
                        break;
                    }
                    VariableBinding vb = response.get(0);
                    if (!vb.getOid().startsWith(currentRoot)) {
                        break;
                    }
                    nodes.add(vb);
                    current = vb.getOid();
                    count++;
                }
                return nodes;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SNMP WALK 执行失败", e);
        }
    }

    protected PDU createPdu(int type, List<SnmpAddress> addresses) {
        PDU pdu = newPduInstance();
        for (SnmpAddress address : addresses) {
            pdu.add(new VariableBinding(new OID(address.getOid())));
        }
        pdu.setType(type);
        return pdu;
    }

    protected PDU validateResponse(ResponseEvent<UdpAddress> event) throws IOException {
        if (event == null || event.getResponse() == null) {
            throw new IOException("SNMP请求超时或无响应");
        }
        PDU response = event.getResponse();
        if (response.getErrorStatus() != PDU.noError) {
            throw new IOException("SNMP错误: " + response.getErrorStatusText());
        }
        return response;
    }

    protected Object convertVariable(Variable variable, SnmpDataType dataType) {
        return SnmpUtils.variableToJava(variable, dataType);
    }

    protected Variable convertForWrite(Object value, SnmpDataType dataType) {
        return SnmpUtils.toVariable(value, dataType);
    }

    protected <T> T executeSnmp(SnmpConnectionAdapter.SnmpCallable<T> callable) throws Exception {
        if (snmpConnection == null) {
            throw new IllegalStateException("SNMP连接尚未建立");
        }
        return snmpConnection.execute(callable, timeout);
    }

    protected Snmp getSnmpClient() {
        return snmpConnection != null ? snmpConnection.getSnmp() : null;
    }

    protected Target<UdpAddress> getSnmpTarget() {
        return snmpConnection != null ? snmpConnection.getTarget() : null;
    }

    private int parseInt(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private PDU newPduInstance() {
        PDU pdu = version == SnmpConstants.version3 ? new ScopedPDU() : new PDU();
        if (pdu instanceof ScopedPDU scoped) {
            if (contextNameOctet != null) {
                scoped.setContextName(contextNameOctet);
            }
            if (contextEngineIdOctet != null) {
                scoped.setContextEngineID(contextEngineIdOctet);
            }
        }
        return pdu;
    }
}
