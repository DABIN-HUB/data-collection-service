package com.wangbin.collector.api.filter.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 生产环境安全配置启动校验器。
 */
@Component
@RequiredArgsConstructor
public class ProductionSecurityValidator {

    private static final String PRODUCTION_PROFILE = "prod";
    private static final String DEFAULT_SECRET = "change-me";

    private final Environment environment;
    private final AuthProperties authProperties;

    /**
     * 校验业务条件和参数边界。
     */
    @PostConstruct
    public void validate() {
        if (Arrays.stream(environment.getActiveProfiles())
                .noneMatch(PRODUCTION_PROFILE::equalsIgnoreCase)) {
            return;
        }
        requireSecret("spring.datasource.password", "TDengine 密码");
        requireSecret("spring.data.redis.password", "Redis 密码");
        if (authProperties.getOpsTokens().isEmpty()) {
            throw new IllegalStateException("生产环境必须配置至少一个运维令牌");
        }
        authProperties.getOpsTokens().forEach((name, token) ->
                validateSecret(token, "运维令牌 " + name));

        boolean mqttEnabled = environment.getProperty(
                "collector.report.mqtt.enabled", Boolean.class, false);
        if (mqttEnabled) {
            requireSecret("collector.report.mqtt.password", "MQTT 密码");
            requireSecret("collector.report.mqtt.gateway-product-key", "网关产品标识");
            requireSecret("collector.report.mqtt.gateway-device-name", "网关设备名称");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void requireSecret(String propertyName, String description) {
        validateSecret(environment.getProperty(propertyName), description);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateSecret(String value, String description) {
        if (value == null || value.isBlank() || DEFAULT_SECRET.equalsIgnoreCase(value.trim())) {
            throw new IllegalStateException("生产环境未安全配置" + description);
        }
    }
}
