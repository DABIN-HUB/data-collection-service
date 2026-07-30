package com.wangbin.collector.core.cloud.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 子设备动态注册状态服务，保存云平台返回的子设备密钥引用。
 */
@Service
public class CloudSubDeviceRegisterService {

    private final ConcurrentMap<String, RegisteredSubDevice> registeredDevices = new ConcurrentHashMap<>();

    public Map<String, Object> applyRegisterReply(JsonNode root) {
        List<RegisteredSubDevice> devices = parseRegisteredDevices(root);
        int changed = 0;
        for (RegisteredSubDevice device : devices) {
            if (device.identity().valid()) {
                registeredDevices.put(device.identity().key(), device);
                changed++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registered", changed);
        data.put("total", registeredDevices.size());
        return data;
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> devices = new ArrayList<>();
        for (RegisteredSubDevice device : registeredDevices.values()) {
            devices.add(device.toMap(false));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", devices.size());
        data.put("devices", devices);
        return data;
    }

    public RegisteredSubDevice get(CloudDeviceIdentity identity) {
        if (identity == null || !identity.valid()) {
            return null;
        }
        return registeredDevices.get(identity.key());
    }

    private List<RegisteredSubDevice> parseRegisteredDevices(JsonNode root) {
        if (root == null || root.isNull()) {
            return Collections.emptyList();
        }
        JsonNode data = firstNode(root, "data", "params", "subList", "subDevices", "devices");
        if (data == null || data.isNull()) {
            data = root;
        }
        List<RegisteredSubDevice> result = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                RegisteredSubDevice device = parseRegisteredDevice(item);
                if (device.identity().valid()) {
                    result.add(device);
                }
            }
        } else if (data.isObject()) {
            RegisteredSubDevice device = parseRegisteredDevice(data);
            if (device.identity().valid()) {
                result.add(device);
            }
        }
        return result;
    }

    private RegisteredSubDevice parseRegisteredDevice(JsonNode node) {
        CloudDeviceIdentity identity = CloudDeviceIdentity.of(
                firstText(node, "productKey", "pk"),
                firstText(node, "deviceName", "dn"));
        String deviceSecret = firstText(node, "deviceSecret", "secret", "secretRef");
        String status = firstText(node, "status", "message", "msg");
        return new RegisteredSubDevice(identity, deviceSecret, status, System.currentTimeMillis());
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
        if (value == null) {
            return null;
        }
        String text = value.asText(null);
        return StringUtils.hasText(text) ? text : null;
    }

    public record RegisteredSubDevice(
            CloudDeviceIdentity identity,
            String deviceSecret,
            String status,
            long registeredAt) {

        public Map<String, Object> toMap(boolean exposeSecret) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("productKey", identity.productKey());
            data.put("deviceName", identity.deviceName());
            data.put("status", status);
            data.put("registeredAt", registeredAt);
            if (exposeSecret) {
                data.put("deviceSecret", deviceSecret);
            } else if (StringUtils.hasText(deviceSecret)) {
                data.put("deviceSecretRef", identity.key());
            }
            return data;
        }
    }
}
