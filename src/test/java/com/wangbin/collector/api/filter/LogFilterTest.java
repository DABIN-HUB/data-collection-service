package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AccessLogProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogFilterTest {

    @Test
    void shouldSkipExcludedPaths() {
        AccessLogProperties properties = new AccessLogProperties();
        properties.setIncludePaths(List.of("/api/**"));
        properties.setExcludePaths(List.of("/health", "/actuator/**"));

        TestableLogFilter filter = new TestableLogFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");

        assertThat(filter.shouldSkip(request)).isTrue();
    }

    @Test
    void shouldLogIncludedPath() {
        AccessLogProperties properties = new AccessLogProperties();
        properties.setIncludePaths(List.of("/api/**"));
        properties.setExcludePaths(List.of("/health"));

        TestableLogFilter filter = new TestableLogFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/config/device/dev-1/clear");

        assertThat(filter.shouldSkip(request)).isFalse();
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
