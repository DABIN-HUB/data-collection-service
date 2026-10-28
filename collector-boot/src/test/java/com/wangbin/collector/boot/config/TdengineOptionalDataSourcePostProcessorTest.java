package com.wangbin.collector.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TdengineOptionalDataSourcePostProcessorTest {

    @Test
    void disabledTdengineExcludesJdbcAutoConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("telemetry.tdengine.enabled", "false")
                .withProperty("spring.autoconfigure.exclude", "example.ExistingAutoConfiguration");

        new TdengineOptionalDataSourcePostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                .contains("example.ExistingAutoConfiguration")
                .contains("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
    }

    @Test
    void enabledTdengineLeavesAutoConfigurationUnchanged() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("telemetry.tdengine.enabled", "true")
                .withProperty("spring.autoconfigure.exclude", "example.ExistingAutoConfiguration");

        new TdengineOptionalDataSourcePostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                .isEqualTo("example.ExistingAutoConfiguration");
    }
}
