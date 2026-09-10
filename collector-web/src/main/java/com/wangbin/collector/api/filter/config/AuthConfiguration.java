package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.AuthFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 鉴权过滤器自动配置。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    public static final int AUTH_FILTER_ORDER = FilterOrders.AUTH;

    /**
     * 执行当前业务逻辑。
     */
    @Bean
    @ConditionalOnProperty(prefix = "collector.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<OncePerRequestFilter> authFilter(AuthProperties properties,
                                                                   ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        OncePerRequestFilter filter = new AuthFilter(properties,
                redisTemplateProvider.getIfAvailable(),
                meterRegistryProvider.getIfAvailable());
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(AUTH_FILTER_ORDER);
        registration.setName("authFilter");
        registration.addUrlPatterns("/*");
        return registration;
    }
}
