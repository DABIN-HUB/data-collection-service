package com.wangbin.collector.api.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wangbin.collector.api.filter.config.AccessLogProperties;
import com.wangbin.collector.api.filter.config.AuthProperties;
import com.wangbin.collector.api.filter.config.AuthScope;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogFilterTest {

    private Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        accessLogger = (Logger) LoggerFactory.getLogger("collector.access");
        previousLevel = accessLogger.getLevel();
        accessLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
        accessLogger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void shouldSkipExcludedPathsWithContextPath() {
        AccessLogProperties properties = new AccessLogProperties();
        properties.setIncludePaths(List.of("/api/**"));
        properties.setExcludePaths(List.of("/health", "/actuator/**"));

        TestableLogFilter filter = new TestableLogFilter(properties);
        MockHttpServletRequest health = request("GET", "/collector/health");
        MockHttpServletRequest actuator = request("GET", "/collector/actuator/health");

        assertThat(filter.shouldSkip(health)).isTrue();
        assertThat(filter.shouldSkip(actuator)).isTrue();
    }

    @Test
    void shouldLogIncludedPathWithContextPath() {
        AccessLogProperties properties = new AccessLogProperties();
        properties.setIncludePaths(List.of("/api/**"));
        properties.setExcludePaths(List.of("/health"));

        TestableLogFilter filter = new TestableLogFilter(properties);
        MockHttpServletRequest request = request("POST", "/collector/api/config/device/dev-1/clear");

        assertThat(filter.shouldSkip(request)).isFalse();
    }

    @Test
    void shouldEmitNormalAccessLogWithApplicationPathAndRequestId() throws Exception {
        AccessLogProperties properties = new AccessLogProperties();
        LogFilter filter = new LogFilter(properties);
        MockHttpServletRequest request = request("GET", "/collector/api/config/devices");
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "obs-052-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_OK));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLoggerName()).isEqualTo("collector.access");
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("requestId=obs-052-001", "方法=GET", "路径=/api/config/devices", "状态=200", "耗时毫秒=")
                .doesNotContain("/collector/api/config/devices");
    }

    @Test
    void shouldLogHighRiskDeniedRequestBeforeControllerSideEffect() throws Exception {
        AccessLogProperties properties = new AccessLogProperties();
        AccessLogProperties.RiskRule rule = new AccessLogProperties.RiskRule();
        rule.setMethod("POST");
        rule.setPattern("/api/config/import");
        properties.setHighRiskRules(List.of(rule));
        LogFilter accessFilter = new LogFilter(properties);
        AuthProperties authProperties = new AuthProperties();
        AuthFilter authFilter = new AuthFilter(authProperties);
        MockHttpServletRequest request = request("POST", "/collector/api/config/import");
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "obs-052-high-risk");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessFilter.doFilter(request, response, (servletRequest, servletResponse) ->
                authFilter.doFilter(servletRequest, servletResponse, new MockFilterChain()));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("requestId=obs-052-high-risk", "路径=/api/config/import", "状态=401", "risk=HIGH");
    }

    @Test
    void shouldIncludePrincipalAfterAuthFilterAllowsRequest() throws Exception {
        AccessLogProperties properties = new AccessLogProperties();
        LogFilter accessFilter = new LogFilter(properties);
        AuthProperties authProperties = new AuthProperties();
        authProperties.getOpsTokens().put("ops-token", "dev-ops");
        authProperties.getOpsScopes().put("dev-ops", List.of(AuthScope.VIEW));
        AuthFilter authFilter = new AuthFilter(authProperties);
        MockHttpServletRequest request = request("GET", "/collector/api/config/devices");
        request.addHeader("X-Collector-Token", "ops-token");
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "obs-052-principal");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessFilter.doFilter(request, response, (servletRequest, servletResponse) ->
                authFilter.doFilter(servletRequest, servletResponse, new MockFilterChain()));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("主体=OPS_TOKEN:dev-ops");
    }

    @Test
    void shouldRedactSensitiveQueryBeforeLogging() throws Exception {
        AccessLogProperties properties = new AccessLogProperties();
        LogFilter filter = new LogFilter(properties);
        MockHttpServletRequest request = request("GET", "/collector/api/ops/logs");
        request.setQueryString("token=abc123&password=hello&normal=value");
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "obs-052-query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("token=***", "password=***", "normal=value")
                .doesNotContain("abc123", "hello");
    }

    @Test
    void shouldRedactSensitiveAdditionalHeaders() throws Exception {
        AccessLogProperties properties = new AccessLogProperties();
        properties.setAdditionalHeaders(List.of("X-Debug-Id", "Authorization", "X-Collector-Token"));
        LogFilter filter = new LogFilter(properties);
        MockHttpServletRequest request = request("GET", "/collector/api/ops/logs");
        request.addHeader("X-Debug-Id", "visible");
        request.addHeader("Authorization", "Bearer unsafe-value");
        request.addHeader("X-Collector-Token", "unsafe-token");
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, "obs-052-header");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("X-Debug-Id=visible", "Authorization=***", "X-Collector-Token=***")
                .doesNotContain("unsafe-value", "unsafe-token");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/collector");
        return request;
    }

    private static class TestableLogFilter extends LogFilter {

        TestableLogFilter(AccessLogProperties properties) {
            super(properties);
        }

        boolean shouldSkip(MockHttpServletRequest request) {
            return shouldNotFilter(request);
        }
    }
}
