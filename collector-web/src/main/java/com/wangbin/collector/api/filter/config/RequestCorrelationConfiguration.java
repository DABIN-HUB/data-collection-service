package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.RequestCorrelationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 请求关联标识过滤器自动配置。
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class RequestCorrelationConfiguration {

    public static final int REQUEST_CORRELATION_FILTER_ORDER = FilterOrders.REQUEST_CORRELATION;

    /**
     * 创建当前组件实例。
     */
    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter(AccessLogProperties properties) {
        FilterRegistrationBean<RequestCorrelationFilter> registration = new FilterRegistrationBean<>(
                new RequestCorrelationFilter(properties));
        registration.setOrder(REQUEST_CORRELATION_FILTER_ORDER);
        registration.setName("requestCorrelationFilter");
        registration.addUrlPatterns("/*");
        return registration;
    }
}
