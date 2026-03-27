package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.LogFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter 装配
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class AccessLogConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "logging.access", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LogFilter accessLogFilter(AccessLogProperties properties) {
        return new LogFilter(properties);
    }
}
