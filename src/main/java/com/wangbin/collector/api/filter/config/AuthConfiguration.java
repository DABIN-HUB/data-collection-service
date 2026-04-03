package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.AuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Auth filter auto configuration.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @ConditionalOnProperty(prefix = "collector.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OncePerRequestFilter authFilter(AuthProperties properties) {
        return new AuthFilter(properties);
    }
}
