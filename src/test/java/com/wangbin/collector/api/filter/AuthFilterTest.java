package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AuthProperties;
import com.wangbin.collector.api.filter.config.AuthScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;

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
    void shouldAllowHealthWithoutCredential() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void shouldAllowHealthWithContextPath() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collector/health");
        request.setContextPath("/collector");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void shouldNotPermitLegacyAdminPathByDefault() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
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
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        request.setContent(body);
        String timestamp = String.valueOf(fixedInstant.toEpochMilli());
        String nonce = "nonce-1";
        String canonical = String.join("\n",
                timestamp,
                nonce,
                "POST",
                "/api/config/device/dev-1",
                "",
                sha256(body));
        String signature = sign("super-secret", canonical);

        request.addHeader("X-Collector-Service", "cloud-config");
        request.addHeader("X-Collector-Timestamp", timestamp);
        request.addHeader("X-Collector-Nonce", nonce);
        request.addHeader("X-Collector-Key-Version", "v1");
        request.addHeader("X-Collector-Signature", signature);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        AuthFilter.AuthPrincipal principal = (AuthFilter.AuthPrincipal) request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        assertThat(principal).isNotNull();
        assertThat(principal.getId()).contains("cloud-config");
    }

    @Test
    void shouldRejectReplayedServiceSignature() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthProperties.ServiceClient client = new AuthProperties.ServiceClient();
        client.setDefaultKey("v1");
        client.setKeys(new LinkedHashMap<>(java.util.Map.of("v1", "super-secret")));
        properties.getServiceClients().put("cloud-config", client);
        Instant fixedInstant = Instant.ofEpochMilli(1775182300000L);
        AuthFilter filter = new AuthFilter(properties, Clock.fixed(fixedInstant, ZoneId.of("UTC")));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(fixedInstant.toEpochMilli());
        String nonce = "nonce-replayed";
        String canonical = String.join("\n", timestamp, nonce, "POST", "/api/config/device/dev-1", "", sha256(body));
        String signature = sign("super-secret", canonical);

        MockHttpServletRequest firstRequest = signedRequest(timestamp, nonce, signature, body);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, new MockFilterChain());
        MockHttpServletRequest secondRequest = signedRequest(timestamp, nonce, signature, body);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(secondResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldIgnoreForwardedAddressFromUntrustedProxy() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.setAllowIpAuthentication(true);
        properties.setIpAllowList(java.util.List.of("10.1.1.10"));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "10.1.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldRecordAuthenticationResultMetrics() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("ops-token", "dev-ops");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC(), null, meterRegistry);

        MockHttpServletRequest allowedRequest = new MockHttpServletRequest("GET", "/api/config/devices");
        allowedRequest.addHeader("X-Collector-Token", "ops-token");
        filter.doFilter(allowedRequest, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest deniedRequest = new MockHttpServletRequest("GET", "/api/config/devices");
        filter.doFilter(deniedRequest, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(meterRegistry.get("collector.auth.requests")
                .tags("result", "allowed", "type", "OPS_TOKEN")
                .counter().count()).isEqualTo(1.0D);
        assertThat(meterRegistry.get("collector.auth.requests")
                .tags("result", "denied", "type", "UNKNOWN")
                .counter().count()).isEqualTo(1.0D);
    }

    @Test
    void shouldRejectAuthenticatedRequestWithoutRequiredScope() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("view-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        AuthProperties.AccessRule rule = new AuthProperties.AccessRule();
        rule.setMethods(List.of("POST"));
        rule.setPaths(List.of("/api/device/**"));
        rule.setRequiredScope(AuthScope.DEVICE_CONTROL);
        properties.setAccessRules(List.of(rule));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/device/device-1/start");
        request.addHeader("X-Collector-Token", "view-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("权限不足");
    }

    private MockHttpServletRequest signedRequest(String timestamp,
                                                 String nonce,
                                                 String signature,
                                                 byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/config/device/dev-1");
        request.setContent(body);
        request.addHeader("X-Collector-Service", "cloud-config");
        request.addHeader("X-Collector-Timestamp", timestamp);
        request.addHeader("X-Collector-Key-Version", "v1");
        request.addHeader("X-Collector-Nonce", nonce);
        request.addHeader("X-Collector-Signature", signature);
        return request;
    }

    private String sha256(byte[] body) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body));
    }

    private String sign(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
