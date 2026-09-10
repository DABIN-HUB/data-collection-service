package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AccessLogProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {

    @AfterEach
    void cleanMdc() {
        MDC.remove(RequestCorrelationFilter.MDC_REQUEST_ID);
    }

    @Test
    void shouldPreserveIncomingSafeRequestId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new AccessLogProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.addHeader("X-Request-Id", "obs-052-001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValue = new AtomicReference<>();
        AtomicReference<Object> attributeValue = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            mdcValue.set(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID));
            attributeValue.set(servletRequest.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID));
        });

        assertThat(mdcValue).hasValue("obs-052-001");
        assertThat(attributeValue).hasValue("obs-052-001");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("obs-052-001");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void shouldGenerateRequestIdWhenHeaderMissing() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new AccessLogProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValue = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                mdcValue.set(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)));

        String generated = response.getHeader("X-Request-Id");
        assertThat(generated).isNotBlank();
        assertThat(request.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID)).isEqualTo(generated);
        assertThat(mdcValue).hasValue(generated);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void shouldRejectUnsafeIncomingRequestId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new AccessLogProperties());
        assertUnsafeHeaderReplaced(filter, "bad\r\nrequest");
        assertUnsafeHeaderReplaced(filter, "x".repeat(129));
        assertUnsafeHeaderReplaced(filter, "obs 052/unsafe");
    }

    @Test
    void shouldRestorePreviousMdcRequestId() throws Exception {
        MDC.put(RequestCorrelationFilter.MDC_REQUEST_ID, "parent-id");
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new AccessLogProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.addHeader("X-Request-Id", "child-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isEqualTo("child-id"));

        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isEqualTo("parent-id");
    }

    @Test
    void shouldCleanupMdcWhenChainThrows() {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new AccessLogProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.addHeader("X-Request-Id", "obs-052-exception");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("obs-052-exception");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNull();
    }

    private void assertUnsafeHeaderReplaced(RequestCorrelationFilter filter, String unsafeValue)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config/devices");
        request.addHeader("X-Request-Id", unsafeValue);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID)).isNotEqualTo(unsafeValue);
            assertThat(servletRequest.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID)).isNotEqualTo(unsafeValue);
        });

        assertThat(response.getHeader("X-Request-Id"))
                .isNotBlank()
                .isNotEqualTo(unsafeValue);
    }
}
