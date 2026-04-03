package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {

    @Test
    void shouldAllowOpsToken() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("ops-token", "dev-ops");

        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.addHeader("X-Collector-Token", "ops-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(request.getAttribute(AuthFilter.ATTR_PRINCIPAL)).isNotNull();
    }

    @Test
    void shouldValidateServiceSignature() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthProperties.ServiceClient client = new AuthProperties.ServiceClient();
        client.setDefaultKey("v1");
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        keys.put("v1", "super-secret");
        client.setKeys(keys);
        client.setAllowIpFallback(false);
        properties.getServiceClients().put("cloud-config", client);

        Instant fixedInstant = Instant.ofEpochMilli(1775182300000L);
        Clock clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        AuthFilter filter = new AuthFilter(properties, clock);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/config/device/dev-1");
        request.setRemoteAddr("10.1.1.10");
        String timestamp = String.valueOf(fixedInstant.toEpochMilli());
        String canonical = timestamp + "\nPOST\n/api/config/device/dev-1\n";
        String signature = sign("super-secret", canonical);

        request.addHeader("X-Collector-Service", "cloud-config");
        request.addHeader("X-Collector-Timestamp", timestamp);
        request.addHeader("X-Collector-Key-Version", "v1");
        request.addHeader("X-Collector-Signature", signature);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        AuthFilter.AuthPrincipal principal = (AuthFilter.AuthPrincipal) request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        assertThat(principal).isNotNull();
        assertThat(principal.getId()).contains("cloud-config");
    }

    private String sign(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
