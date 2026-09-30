package com.wangbin.collector.api.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.filter.config.AuthProperties;
import com.wangbin.collector.api.filter.config.AuthScope;
import com.wangbin.collector.api.filter.config.AccessLogProperties;
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
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {

    @Test
    void rejectedRequestSharesGeneratedIdAcrossHeaderAndErrorBody() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/config/devices", null);
        MockHttpServletResponse response = correlatedAuth(new AuthProperties(), request);

        assertError(response, 401, "AUTH_REQUIRED");
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("message").asText())
                .isEqualTo("认证失败");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void deniedScopeIncludesRequiredScopeAndRequestId() throws Exception {
        AuthProperties properties = controlAndShadowAuthProperties();
        properties.getOpsTokens().put("view-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        MockHttpServletRequest request = request("POST", "/api/control/device/dev-1/point/p1", "view-token");
        request.addHeader("X-Request-Id", "abc-123");

        MockHttpServletResponse response = correlatedAuth(properties, request);

        assertError(response, 403, "PERMISSION_DENIED");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("data")
                .path("requiredScope").asText()).isEqualTo("DEVICE_CONTROL");
    }

    @Test
    void oversizedSignedBodyUsesSameErrorContract() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.setMaxSignedBodyBytes(1);
        MockHttpServletRequest request = request("POST", "/api/config/device/dev-1", null);
        request.addHeader(properties.getServiceHeader(), "client-1");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertError(correlatedAuth(properties, request), 413, "REQUEST_BODY_TOO_LARGE");
    }

    private MockHttpServletResponse correlatedAuth(AuthProperties properties, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestCorrelationFilter correlation = new RequestCorrelationFilter(new AccessLogProperties());
        AuthFilter auth = new AuthFilter(properties, Clock.systemUTC());
        correlation.doFilter(request, response, (servletRequest, servletResponse) ->
                auth.doFilter(servletRequest, servletResponse, new MockFilterChain()));
        return response;
    }

    private void assertError(MockHttpServletResponse response, int code, String machineCode) throws Exception {
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(code);
        assertThat(body.path("code").asInt()).isEqualTo(code);
        assertThat(body.path("status").asText()).isEqualTo("error");
        assertThat(body.path("machineCode").asText()).isEqualTo(machineCode);
        assertThat(body.path("extra").path("requestId").asText()).isNotBlank()
                .isEqualTo(response.getHeader("X-Request-Id"));
    }

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
    void shouldAllowDesktopStaticResourcesWithContextPath() throws Exception {
        AuthProperties properties = new AuthProperties();
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collector/desktop/index.html");
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


    @Test
    void shouldHardenActuatorExposureAndKeepHealthPublic() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("view-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        AuthProperties.AccessRule rule = new AuthProperties.AccessRule();
        rule.setMethods(List.of("GET"));
        rule.setPaths(List.of("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus", "/monitor/pipeline"));
        rule.setRequiredScope(AuthScope.VIEW);
        properties.setAccessRules(List.of(rule));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());

        assertThat(status(filter, request("GET", "/actuator/health", null))).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/actuator/health/liveness", null))).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/actuator/health/readiness", null))).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/actuator/metrics", null))).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(status(filter, request("GET", "/actuator/metrics/jvm.memory.used", null))).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(status(filter, request("GET", "/actuator/prometheus", null))).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(status(filter, request("GET", "/monitor/pipeline", null))).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(status(filter, request("GET", "/actuator/metrics", "view-token"))).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/actuator/prometheus", "view-token"))).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/monitor/pipeline", "view-token"))).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void rejectedActuatorRequestShouldStillHaveRequestIdWhenCorrelationFilterRunsFirst() throws Exception {
        AuthProperties properties = new AuthProperties();
        AccessLogProperties accessLogProperties = new AccessLogProperties();
        RequestCorrelationFilter correlationFilter = new RequestCorrelationFilter(accessLogProperties);
        AuthFilter authFilter = new AuthFilter(properties, Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("X-Request-Id", "obs-053-denied");
        MockHttpServletResponse response = new MockHttpServletResponse();

        correlationFilter.doFilter(request, response, (servletRequest, servletResponse) ->
                authFilter.doFilter(servletRequest, servletResponse, new MockFilterChain()));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("obs-053-denied");
    }

    @Test
    void shouldProtectControlAndShadowWritesByDeviceControlScope() throws Exception {
        AuthProperties properties = controlAndShadowAuthProperties();
        properties.getOpsTokens().put("viewer-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        properties.getOpsTokens().put("operator-token", "operator");
        properties.getOpsScopes().put("operator", List.of(AuthScope.VIEW, AuthScope.DEVICE_CONTROL));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());

        assertThat(status(filter, request("POST", "/api/control/device/dev-1/point/p1", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(filter, request("POST", "/api/control/device/dev-1/points", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(filter, request("POST", "/api/control/device/dev-1/command", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);

        assertThat(status(filter, request("GET", "/api/shadow/dev-1", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/api/shadow/dev-1/delta", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("GET", "/api/shadow/dev-1/history", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("POST", "/api/shadow/dev-1/desired", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(filter, request("DELETE", "/api/shadow/dev-1/desired", "viewer-token")))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);

        assertThat(status(filter, request("POST", "/api/control/device/dev-1/point/p1", "operator-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("POST", "/api/control/device/dev-1/points", "operator-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("POST", "/api/control/device/dev-1/command", "operator-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("POST", "/api/shadow/dev-1/desired", "operator-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(status(filter, request("DELETE", "/api/shadow/dev-1/desired", "operator-token")))
                .isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void shouldApplyControlAndShadowRulesAfterContextPath() throws Exception {
        AuthProperties properties = controlAndShadowAuthProperties();
        properties.getOpsTokens().put("viewer-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        properties.getOpsTokens().put("operator-token", "operator");
        properties.getOpsScopes().put("operator", List.of(AuthScope.VIEW, AuthScope.DEVICE_CONTROL));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());

        MockHttpServletRequest viewerRequest = request("POST", "/collector/api/control/device/dev-1/point/p1", "viewer-token");
        viewerRequest.setContextPath("/collector");
        assertThat(status(filter, viewerRequest)).isEqualTo(HttpServletResponse.SC_FORBIDDEN);

        MockHttpServletRequest operatorRequest = request("POST", "/collector/api/control/device/dev-1/point/p1", "operator-token");
        operatorRequest.setContextPath("/collector");
        assertThat(status(filter, operatorRequest)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void shouldSeparateCloudViewAndOperateScopes() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("viewer-token", "viewer");
        properties.getOpsScopes().put("viewer", List.of(AuthScope.VIEW));
        properties.getOpsTokens().put("cloud-operator-token", "operator");
        properties.getOpsScopes().put("operator", List.of(AuthScope.VIEW, AuthScope.CLOUD_OPERATE));
        properties.setAccessRules(List.of(
                accessRule(List.of("POST"), List.of("/api/cloud/**"), AuthScope.CLOUD_OPERATE),
                accessRule(List.of("GET"), List.of("/api/**", "/monitor/**"), AuthScope.VIEW)));
        AuthFilter filter = new AuthFilter(properties, Clock.systemUTC());

        assertThat(status(filter, request("GET", "/api/cloud/outbox", "viewer-token"))).isEqualTo(200);
        assertThat(status(filter, request("GET", "/api/cloud/outbox/msg-1", "viewer-token"))).isEqualTo(200);
        for (String path : List.of("/api/cloud/outbox/msg-1/replay", "/api/cloud/flush", "/api/cloud/test")) {
            assertThat(status(filter, request("POST", path, "viewer-token"))).isEqualTo(403);
            assertThat(status(filter, request("POST", path, "cloud-operator-token"))).isEqualTo(200);
        }
    }

    private AuthProperties controlAndShadowAuthProperties() {
        AuthProperties properties = new AuthProperties();
        properties.setAccessRules(List.of(
                accessRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                        List.of("/api/device/**", "/api/data/device/*/reset-adaptive"), AuthScope.DEVICE_CONTROL),
                accessRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                        List.of("/api/control/**"), AuthScope.DEVICE_CONTROL),
                accessRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                        List.of("/api/shadow/**"), AuthScope.DEVICE_CONTROL),
                accessRule(List.of("POST", "PUT", "PATCH", "DELETE"),
                        List.of("/api/config/**"), AuthScope.CONFIG_MANAGE),
                accessRule(List.of("GET"), List.of("/api/**", "/monitor/**"), AuthScope.VIEW)));
        return properties;
    }

    private AuthProperties.AccessRule accessRule(List<String> methods,
                                                  List<String> paths,
                                                  AuthScope scope) {
        AuthProperties.AccessRule rule = new AuthProperties.AccessRule();
        rule.setMethods(methods);
        rule.setPaths(paths);
        rule.setRequiredScope(scope);
        return rule;
    }
    private MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) {
            request.addHeader("X-Collector-Token", token);
        }
        return request;
    }

    private int status(AuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
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
