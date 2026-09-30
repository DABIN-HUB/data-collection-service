package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.LogFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter 装配
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class AccessLogConfiguration {

    public static final int ACCESS_LOG_FILTER_ORDER = FilterOrders.ACCESS_LOG;

    /**
     * 执行当前业务逻辑。
     */
    @Bean
    @ConditionalOnProperty(prefix = "logging.access", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<LogFilter> accessLogFilter(AccessLogProperties properties) {
        FilterRegistrationBean<LogFilter> registration = new FilterRegistrationBean<>(new LogFilter(properties));
        registration.setOrder(ACCESS_LOG_FILTER_ORDER);
        registration.setName("accessLogFilter");
        registration.addUrlPatterns("/*");
        return registration;
    }
}
