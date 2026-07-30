package com.wangbin.collector.core.collector.protocol.snmp;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.snmp.base.AbstractSnmpCollector;
import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpAddress;
import com.wangbin.collector.core.collector.protocol.snmp.domain.SnmpDataType;
import com.wangbin.collector.core.collector.protocol.snmp.util.SnmpAddressParser;
import com.wangbin.collector.core.collector.protocol.snmp.util.SnmpUtils;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.PDU;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SNMP 采集器。
 * 订阅点位会按 OID 建立 Trap/Inform 映射，收到推送后进入统一遥测入口。
 */
@Slf4j
public class SnmpCollector extends AbstractSnmpCollector {

    private final Map<String, DataPoint> subscribedPoints = new ConcurrentHashMap<>();
    private final Map<String, DataPoint> subscribedOidIndex = new ConcurrentHashMap<>();
    private final SnmpConnectionAdapter.SnmpTrapListener trapListener = this::handleTrapOrInform;

    @Override
    public String getCollectorType() {
        return "SNMP";
    }

    @Override
    public String getProtocolType() {
        return "SNMP";
    }

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = initSnmpConfig(deviceInfo);
        initSnmpConnection(connectionConfig);
        if (snmpConnection != null) {
            snmpConnection.addTrapListener(trapListener);
        }
        subscribedPoints.values().forEach(this::indexSubscribedPoint);
    }

    @Override
    protected void doDisconnect() {
        if (snmpConnection != null) {
            snmpConnection.removeTrapListener(trapListener);
        }
        closeSnmpConnection();
    }

    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        SnmpAddress address = SnmpAddressParser.parse(point);
        Map<String, Variable> values = performGet(List.of(address));
        Variable variable = values.get(address.getOid());
        return convertVariable(variable, address.getDataType());
    }

    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (points.isEmpty()) {
            return result;
        }
        List<SnmpAddress> addresses = new ArrayList<>(points.size());
        Map<String, SnmpAddress> byPoint = new HashMap<>();
        for (DataPoint point : points) {
            SnmpAddress address = SnmpAddressParser.parse(point);
            addresses.add(address);
            byPoint.put(point.getPointId(), address);
        }
        Map<String, Variable> values = performGet(addresses);
        for (DataPoint point : points) {
            SnmpAddress address = byPoint.get(point.getPointId());
            Variable variable = values.get(address.getOid());
            Object value = convertVariable(variable, address.getDataType());
            result.put(point.getPointId(), value);
        }
        return result;
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        SnmpAddress address = SnmpAddressParser.parse(point);
        performSet(Map.of(address, value));
        return true;
    }

    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        Map<String, Boolean> results = new HashMap<>();
        if (points.isEmpty()) {
            return results;
        }
        Map<SnmpAddress, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            SnmpAddress address = SnmpAddressParser.parse(entry.getKey());
            payload.put(address, entry.getValue());
        }
        performSet(payload);
        for (DataPoint point : points.keySet()) {
            results.put(point.getPointId(), true);
        }
        return results;
    }

    @Override
    protected void doSubscribe(List<DataPoint> points) {
        for (DataPoint point : points) {
            subscribedPoints.put(point.getPointId(), point);
            indexSubscribedPoint(point);
        }
        log.info("SNMP订阅登记完成, size={}", points.size());
    }

    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            subscribedPoints.clear();
            subscribedOidIndex.clear();
        } else {
            for (DataPoint point : points) {
                subscribedPoints.remove(point.getPointId());
                unindexSubscribedPoint(point);
            }
        }
        log.info("SNMP取消订阅, size={}", points != null ? points.size() : 0);
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("host", host);
        status.put("port", port);
        status.put("community", community);
        status.put("version", version);
        status.put("connected", snmpConnection != null && snmpConnection.isConnected());
        status.put("subscribedPoints", subscribedPoints.size());
        status.put("subscribedOids", subscribedOidIndex.size());
        status.put("timeout", timeout);
        status.put("retries", retries);
        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        switch (command.toLowerCase()) {
            case "get":
                return executeGet(params);
            case "set":
                return executeSet(params);
            case "walk":
                return executeWalk(params);
            default:
                throw new IllegalArgumentException("不支持的SNMP命令: " + command);
        }
    }

    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        log.info("SNMP加载点位 deviceId={} size={}", deviceId, points.size());
    }

    void handleTrapBindings(List<VariableBinding> bindings, Address peerAddress) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (VariableBinding binding : bindings) {
            if (binding == null || binding.getOid() == null) {
                continue;
            }
            String oid = normalizeOid(binding.getOid().toDottedString());
            DataPoint point = subscribedOidIndex.get(oid);
            if (point == null) {
                continue;
            }
            try {
                SnmpAddress address = SnmpAddressParser.parse(point);
                Object value = convertVariable(binding.getVariable(), address.getDataType());
                ingestPushedValue(point, value);
                log.debug("SNMP Trap/Inform 入站: peer={}, oid={}, pointId={}, value={}",
                        peerAddress, oid, point.getPointId(), value);
            } catch (Exception e) {
                log.warn("SNMP Trap/Inform 点位处理失败: oid={}, pointId={}", oid, point.getPointId(), e);
            }
        }
    }

    private void handleTrapOrInform(PDU pdu, List<VariableBinding> bindings, Address peerAddress) {
        handleTrapBindings(bindings, peerAddress);
    }

    private void indexSubscribedPoint(DataPoint point) {
        try {
            SnmpAddress address = SnmpAddressParser.parse(point);
            subscribedOidIndex.put(normalizeOid(address.getOid()), point);
        } catch (Exception e) {
            log.warn("SNMP订阅点位OID解析失败: pointId={}, address={}",
                    point != null ? point.getPointId() : null,
                    point != null ? point.getAddress() : null,
                    e);
        }
    }

    private void unindexSubscribedPoint(DataPoint point) {
        try {
            SnmpAddress address = SnmpAddressParser.parse(point);
            subscribedOidIndex.remove(normalizeOid(address.getOid()));
        } catch (Exception e) {
            log.debug("SNMP取消订阅点位OID解析失败: pointId={}",
                    point != null ? point.getPointId() : null,
                    e);
        }
    }

    private String normalizeOid(String oid) {
        if (oid == null) {
            return "";
        }
        String normalized = oid.trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private Object executeGet(Map<String, Object> params) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> oids = params.containsKey("oids")
                ? (List<String>) params.get("oids")
                : null;
        if (oids == null || oids.isEmpty()) {
            Object single = params.get("oid");
            if (single == null) {
                throw new IllegalArgumentException("缺少oid参数");
            }
            oids = List.of(single.toString());
        }
        List<SnmpAddress> addresses = new ArrayList<>();
        for (String oid : oids) {
            addresses.add(new SnmpAddress(oid, SnmpDataType.AUTO));
        }
        Map<String, Variable> values = performGet(addresses);
        List<Map<String, Object>> response = new ArrayList<>();
        for (String oid : oids) {
            Variable variable = values.get(oid);
            response.add(Map.of(
                    "oid", oid,
                    "value", convertVariable(variable, SnmpDataType.AUTO),
                    "type", variable != null ? variable.getSyntaxString() : "null"
            ));
        }
        return response;
    }

    private Object executeSet(Map<String, Object> params) throws IOException {
        Object oid = params.get("oid");
        Object value = params.get("value");
        String type = params.getOrDefault("type", "").toString();
        if (oid == null || value == null) {
            throw new IllegalArgumentException("缺少oid或value参数");
        }
        SnmpAddress address = new SnmpAddress(oid.toString(), SnmpDataType.fromText(type));
        performSet(Map.of(address, value));
        return Map.of("status", "success", "oid", address.getOid());
    }

    private Object executeWalk(Map<String, Object> params) throws IOException {
        Object oid = params.get("oid");
        if (oid == null) {
            throw new IllegalArgumentException("缺少oid参数");
        }
        int limit = params.getOrDefault("limit", 50) instanceof Number n
                ? n.intValue() : 50;
        List<VariableBinding> bindings = performWalk(oid.toString(), limit);
        List<Map<String, Object>> values = new ArrayList<>();
        for (VariableBinding binding : bindings) {
            values.add(Map.of(
                    "oid", binding.getOid().toDottedString(),
                    "value", SnmpUtils.variableToJava(binding.getVariable(), SnmpDataType.AUTO),
                    "type", binding.getVariable().getSyntaxString()
            ));
        }
        return Map.of("count", values.size(), "values", values);
    }
}
