package com.wangbin.collector.core.config.security;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 配置敏感字段递归脱敏与写入掩码恢复。 */
@Component
public class SensitiveConfigSanitizer {
    public static final String MASKED_VALUE = "******";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "devicesecret", "authtoken", "token", "secret",
            "apikey", "accesskey", "accesskeysecret", "privatekey", "clientsecret");

    /** 返回独立投影，不修改缓存里的认证信息。 */
    public DeviceInfo sanitize(DeviceInfo source) {
        if (source == null) return null;
        DeviceInfo target = new DeviceInfo();
        BeanUtils.copyProperties(source, target);
        target.setAuthConfig(sanitizeMap(source.getAuthConfig()));
        return target;
    }

    public DeviceConnection sanitize(DeviceConnection source) {
        if (source == null) return null;
        DeviceConnection target = new DeviceConnection();
        BeanUtils.copyProperties(source, target);
        target.setPassword(mask(source.getPassword()));
        target.setDeviceSecret(mask(source.getDeviceSecret()));
        target.setAuthToken(mask(source.getAuthToken()));
        target.setExtJson(sanitizeMap(source.getExtJson()));
        if (source.getAuthParams() != null) {
            Map<String, String> params = new LinkedHashMap<>();
            source.getAuthParams().forEach((key, value) -> params.put(key, isSensitive(key) ? mask(value) : value));
            target.setAuthParams(params);
        }
        return target;
    }

    /** 调用方必须在提交的同一写锁、版本校验之后恢复原值。 */
    public void restoreMaskedValues(DeviceInfo incoming, DeviceInfo existing) {
        if (incoming != null) incoming.setAuthConfig(restoreMap(incoming.getAuthConfig(),
                existing == null ? null : existing.getAuthConfig()));
    }

    public void restoreMaskedValues(DeviceConnection incoming, DeviceConnection existing) {
        if (incoming == null) return;
        incoming.setPassword(restoreSecret(incoming.getPassword(), existing == null ? null : existing.getPassword()));
        incoming.setDeviceSecret(restoreSecret(incoming.getDeviceSecret(), existing == null ? null : existing.getDeviceSecret()));
        incoming.setAuthToken(restoreSecret(incoming.getAuthToken(), existing == null ? null : existing.getAuthToken()));
        incoming.setExtJson(restoreMap(incoming.getExtJson(), existing == null ? null : existing.getExtJson()));
        if (incoming.getAuthParams() != null) {
            Map<String, String> params = new LinkedHashMap<>();
            incoming.getAuthParams().forEach((key, value) -> params.put(key, isSensitive(key)
                    ? restoreSecret(value, existing == null || existing.getAuthParams() == null ? null : existing.getAuthParams().get(key)) : value));
            incoming.setAuthParams(params);
        }
    }

    private String mask(String value) {
        return value == null || value.isBlank() ? null : MASKED_VALUE;
    }

    private String restoreSecret(String value, String existing) {
        if (!MASKED_VALUE.equals(value)) return value;
        if (existing == null) throw new IllegalArgumentException("敏感字段掩码没有对应原值");
        return existing;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null) return null;
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((key, value) -> target.put(key, isSensitive(key)
                ? mask(value == null ? null : String.valueOf(value)) : sanitizeValue(value)));
        return target;
    }

    /** 保持 JSON 对象和数组形状，不扁平化嵌套配置。 */
    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> target = new LinkedHashMap<>();
            map.forEach((key, item) -> target.put(String.valueOf(key), isSensitive(String.valueOf(key))
                    ? mask(item == null ? null : String.valueOf(item)) : sanitizeValue(item)));
            return target;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sanitizeValue).toList();
        return value;
    }

    private Map<String, Object> restoreMap(Map<String, Object> incoming, Map<String, Object> existing) {
        if (incoming == null) return null;
        Map<String, Object> target = new LinkedHashMap<>();
        incoming.forEach((key, value) -> {
            Object previous = existing == null ? null : existing.get(key);
            if (isSensitive(key) && MASKED_VALUE.equals(value)) {
                if (previous == null) throw new IllegalArgumentException("敏感字段掩码没有对应原值");
                target.put(key, previous);
            } else target.put(key, restoreValue(value, previous));
        });
        return target;
    }

    private Object restoreValue(Object incoming, Object existing) {
        if (incoming instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            Map<String, Object> previous = new LinkedHashMap<>();
            if (existing instanceof Map<?, ?> old) old.forEach((key, value) -> previous.put(String.valueOf(key), value));
            return restoreMap(values, previous);
        }
        if (incoming instanceof List<?> list) {
            List<?> previous = existing instanceof List<?> old ? old : List.of();
            List<Object> target = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                target.add(restoreValue(list.get(index), index < previous.size() ? previous.get(index) : null));
            }
            return target;
        }
        return incoming;
    }

    private boolean isSensitive(String key) {
        if (key == null) return false;
        return SENSITIVE_KEYS.contains(key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT));
    }
}
