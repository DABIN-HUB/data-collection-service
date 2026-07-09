package com.wangbin.collector.core.cloud.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 云平台拓扑状态服务，负责维护网关与子设备的当前关系。
 */
@Service
public class CloudTopologyService {

    private final ConcurrentMap<String, Set<CloudDeviceIdentity>> gatewayTopologies = new ConcurrentHashMap<>();

    public Map<String, Object> addSubDevices(CloudDeviceIdentity gateway, List<CloudDeviceIdentity> subDevices) {
        if (gateway == null || !gateway.valid() || subDevices == null || subDevices.isEmpty()) {
            return result(gateway, 0, "empty topology add request");
        }
        Set<CloudDeviceIdentity> topology = gatewayTopologies.computeIfAbsent(
                gateway.key(), key -> ConcurrentHashMap.newKeySet());
        int changed = 0;
        for (CloudDeviceIdentity subDevice : subDevices) {
            if (subDevice != null && subDevice.valid() && topology.add(subDevice)) {
                changed++;
            }
        }
        return result(gateway, changed, "topology added");
    }

    public Map<String, Object> deleteSubDevices(CloudDeviceIdentity gateway, List<CloudDeviceIdentity> subDevices) {
        if (gateway == null || !gateway.valid() || subDevices == null || subDevices.isEmpty()) {
            return result(gateway, 0, "empty topology delete request");
        }
        Set<CloudDeviceIdentity> topology = gatewayTopologies.get(gateway.key());
        int changed = 0;
        if (topology != null) {
            for (CloudDeviceIdentity subDevice : subDevices) {
                if (subDevice != null && subDevice.valid() && topology.remove(subDevice)) {
                    changed++;
                }
            }
        }
        return result(gateway, changed, "topology deleted");
    }

    public Map<String, Object> applyChange(CloudDeviceIdentity gateway, JsonNode params) {
        List<CloudDeviceIdentity> subDevices = parseSubDevices(params);
        boolean online = resolveOnline(params);
        return online ? addSubDevices(gateway, subDevices) : deleteSubDevices(gateway, subDevices);
    }

    public List<CloudDeviceIdentity> listSubDevices(CloudDeviceIdentity gateway) {
        if (gateway == null || !gateway.valid()) {
            return Collections.emptyList();
        }
        Set<CloudDeviceIdentity> topology = gatewayTopologies.get(gateway.key());
        if (topology == null || topology.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(topology);
    }

    public List<CloudDeviceIdentity> parseSubDevices(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        JsonNode candidates = firstNode(node, "subList", "subDevices", "devices", "data");
        if (candidates == null || candidates.isMissingNode() || candidates.isNull()) {
            candidates = node;
        }
        List<CloudDeviceIdentity> result = new ArrayList<>();
        if (candidates.isArray()) {
            for (JsonNode item : candidates) {
                CloudDeviceIdentity identity = parseIdentity(item);
                if (identity.valid()) {
                    result.add(identity);
                }
            }
        } else if (candidates.isObject()) {
            CloudDeviceIdentity identity = parseIdentity(candidates);
            if (identity.valid()) {
                result.add(identity);
            }
        }
        return result;
    }

    public Map<String, Object> snapshot(CloudDeviceIdentity gateway) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gateway", gateway != null ? gateway.key() : null);
        List<Map<String, String>> subDevices = new ArrayList<>();
        for (CloudDeviceIdentity identity : listSubDevices(gateway)) {
            subDevices.add(Map.of(
                    "productKey", identity.productKey(),
                    "deviceName", identity.deviceName()));
        }
        data.put("subDevices", subDevices);
        data.put("count", subDevices.size());
        return data;
    }

    private CloudDeviceIdentity parseIdentity(JsonNode node) {
        if (node == null || node.isNull()) {
            return CloudDeviceIdentity.of("", "");
        }
        return CloudDeviceIdentity.of(firstText(node, "productKey", "pk"), firstText(node, "deviceName", "dn"));
    }

    private boolean resolveOnline(JsonNode params) {
        String status = firstText(params, "status", "topoStatus", "action", "operation");
        if (!StringUtils.hasText(status)) {
            JsonNode value = firstNode(params, "online");
            return value == null || !value.isBoolean() || value.booleanValue();
        }
        String normalized = status.trim().toLowerCase();
        // 阿里云拓扑变更通知中 status=0 表示创建，status=1 表示删除。
        if (Set.of("0", "add", "create", "created", "online", "bind", "enable", "true").contains(normalized)) {
            return true;
        }
        if (Set.of("1", "delete", "remove", "removed", "offline", "unbind", "disable", "false").contains(normalized)) {
            return false;
        }
        return true;
    }

    private JsonNode firstNode(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        JsonNode value = firstNode(node, fields);
        return value == null ? null : value.asText(null);
    }

    private Map<String, Object> result(CloudDeviceIdentity gateway, int changed, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gateway", gateway != null ? gateway.key() : null);
        data.put("changed", changed);
        data.put("message", message);
        if (gateway != null && gateway.valid()) {
            data.put("currentCount", gatewayTopologies.getOrDefault(gateway.key(), new LinkedHashSet<>()).size());
        }
        return data;
    }
}
