package com.wangbin.collector.api.filter.config;

import com.wangbin.collector.api.filter.AuthFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 鉴权过滤器自动配置。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    /**
     * 执行当前业务逻辑。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @ConditionalOnProperty(prefix = "collector.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OncePerRequestFilter authFilter(AuthProperties properties,
                                           ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                           ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new AuthFilter(properties,
                redisTemplateProvider.getIfAvailable(),
                meterRegistryProvider.getIfAvailable());
    }
}
