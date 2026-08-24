package com.wangbin.collector.core.config.security;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SensitiveConfigSanitizerTest {

    private final SensitiveConfigSanitizer sanitizer = new SensitiveConfigSanitizer();

    @Test
    void shouldMaskDirectAndExtendedSecrets() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUsername("collector");
        connection.setPassword("password-value");
        connection.setDeviceSecret("device-secret");
        connection.setExtJson(Map.of("apiKey", "api-key", "channel", "main"));
        connection.setAuthParams(Map.of("clientSecret", "client-secret", "scope", "read"));

        DeviceConnection sanitized = sanitizer.sanitize(connection);

        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE, sanitized.getPassword());
        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE, sanitized.getDeviceSecret());
        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE, sanitized.getExtJson().get("apiKey"));
        assertEquals("main", sanitized.getExtJson().get("channel"));
        assertEquals(SensitiveConfigSanitizer.MASKED_VALUE, sanitized.getAuthParams().get("clientSecret"));
        assertEquals("password-value", connection.getPassword());
    }

    @Test
    void shouldRestoreMaskedValuesWithoutChangingExplicitNewValues() {
        DeviceConnection existing = new DeviceConnection();
        existing.setPassword("old-password");
        existing.setDeviceSecret("old-secret");
        existing.setAuthToken("old-token");
        existing.setAuthParams(Map.of("clientSecret", "old-client-secret"));

        DeviceConnection incoming = new DeviceConnection();
        incoming.setPassword(SensitiveConfigSanitizer.MASKED_VALUE);
        incoming.setDeviceSecret("new-secret");
        incoming.setAuthToken(null);
        incoming.setAuthParams(Map.of("clientSecret", SensitiveConfigSanitizer.MASKED_VALUE));

        sanitizer.restoreMaskedValues(incoming, existing);

        assertEquals("old-password", incoming.getPassword());
        assertEquals("new-secret", incoming.getDeviceSecret());
        assertNull(incoming.getAuthToken());
        assertEquals("old-client-secret", incoming.getAuthParams().get("clientSecret"));
    }
}
