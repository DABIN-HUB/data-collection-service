package com.wangbin.collector.api.filter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityValidatorTest {

    @Test
    void shouldRejectProductionWithoutOpsToken() {
        MockEnvironment environment = productionEnvironment();
        AuthProperties properties = new AuthProperties();
        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, properties);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("运维令牌");
    }

    @Test
    void shouldAcceptProductionWithRequiredSecrets() {
        MockEnvironment environment = productionEnvironment();
        AuthProperties properties = new AuthProperties();
        properties.getOpsTokens().put("main", "strong-ops-token");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment, properties);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "strong-database-password")
                .withProperty("spring.data.redis.password", "strong-redis-password")
                .withProperty("collector.report.mqtt.enabled", "false");
        environment.setActiveProfiles("prod");
        return environment;
    }
}
