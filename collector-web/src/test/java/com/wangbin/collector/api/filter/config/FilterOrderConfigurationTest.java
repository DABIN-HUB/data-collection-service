package com.wangbin.collector.api.filter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterOrderConfigurationTest {

    @Test
    void shouldRegisterCorrelationBeforeAccessBeforeAuth() {
        assertThat(RequestCorrelationConfiguration.REQUEST_CORRELATION_FILTER_ORDER)
                .isLessThan(AccessLogConfiguration.ACCESS_LOG_FILTER_ORDER);
        assertThat(AccessLogConfiguration.ACCESS_LOG_FILTER_ORDER)
                .isLessThan(AuthConfiguration.AUTH_FILTER_ORDER);
    }
}
