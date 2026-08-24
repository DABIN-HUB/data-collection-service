package com.wangbin.collector.core.config.security;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 连接配置敏感字段脱敏器。
 */
@Component
public class SensitiveConfigSanitizer {

    public static final String MASKED_VALUE = "******";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "devicesecret", "authtoken", "token", "secret",
            "apikey", "accesskey", "accesskeysecret", "privatekey", "clientsecret");

    /**
     * 执行当前业务逻辑。
     */
    public DeviceConnection sanitize(DeviceConnection source) {
        if (source == null) {
            return null;
        }
        DeviceConnection target = new DeviceConnection();
        BeanUtils.copyProperties(source, target);
        target.setPassword(mask(source.getPassword()));
        target.setDeviceSecret(mask(source.getDeviceSecret()));
        target.setAuthToken(mask(source.getAuthToken()));
        target.setExtJson(sanitizeMap(source.getExtJson()));
        target.setAuthParams(sanitizeStringMap(source.getAuthParams()));
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    public void restoreMaskedValues(DeviceConnection incoming, DeviceConnection existing) {
        if (incoming == null || existing == null) {
            return;
        }
        if (MASKED_VALUE.equals(incoming.getPassword())) {
            incoming.setPassword(existing.getPassword());
        }
        if (MASKED_VALUE.equals(incoming.getDeviceSecret())) {
            incoming.setDeviceSecret(existing.getDeviceSecret());
        }
        if (MASKED_VALUE.equals(incoming.getAuthToken())) {
            incoming.setAuthToken(existing.getAuthToken());
        }
        incoming.setExtJson(restoreMap(incoming.getExtJson(), existing.getExtJson()));
        incoming.setAuthParams(restoreStringMap(incoming.getAuthParams(), existing.getAuthParams()));
    }

    /**
     * 执行当前业务逻辑。
     */
    private String mask(String value) {
        return value == null || value.isBlank() ? null : MASKED_VALUE;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((key, value) -> target.put(key,
                isSensitive(key) ? mask(value == null ? null : String.valueOf(value)) : value));
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, String> sanitizeStringMap(Map<String, String> source) {
        if (source == null) {
            return null;
        }
        Map<String, String> target = new LinkedHashMap<>();
        source.forEach((key, value) -> target.put(key, isSensitive(key) ? mask(value) : value));
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> restoreMap(Map<String, Object> incoming, Map<String, Object> existing) {
        if (incoming == null || existing == null) {
            return incoming;
        }
        Map<String, Object> target = new LinkedHashMap<>(incoming);
        target.replaceAll((key, value) -> isSensitive(key) && MASKED_VALUE.equals(value)
                ? existing.get(key) : value);
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, String> restoreStringMap(Map<String, String> incoming, Map<String, String> existing) {
        if (incoming == null || existing == null) {
            return incoming;
        }
        Map<String, String> target = new LinkedHashMap<>(incoming);
        target.replaceAll((key, value) -> isSensitive(key) && MASKED_VALUE.equals(value)
                ? existing.get(key) : value);
        return target;
    }

    private boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized);
    }
}
