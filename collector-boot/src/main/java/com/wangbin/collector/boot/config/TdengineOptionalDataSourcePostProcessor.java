package com.wangbin.collector.boot.config;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 历史库存储关闭时阻止 JDBC 自动配置，避免默认 TDengine 地址进入启动依赖链。
 */
public final class TdengineOptionalDataSourcePostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final String TDENGINE_ENABLED_PROPERTY = "telemetry.tdengine.enabled";
    private static final String AUTOCONFIG_EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";
    private static final String DATA_SOURCE_AUTOCONFIGURATION =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";
    private static final String PROPERTY_SOURCE_NAME = "tdengineOptionalDataSourceExclusions";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        if (environment.getProperty(TDENGINE_ENABLED_PROPERTY, Boolean.class, false)) {
            return;
        }

        Set<String> exclusions = new LinkedHashSet<>();
        String[] configuredExclusions = environment.getProperty(
                AUTOCONFIG_EXCLUDE_PROPERTY, String[].class);
        if (configuredExclusions != null) {
            for (String configuredExclusion : configuredExclusions) {
                if (configuredExclusion != null && !configuredExclusion.isBlank()) {
                    exclusions.add(configuredExclusion.trim());
                }
            }
        }
        exclusions.add(DATA_SOURCE_AUTOCONFIGURATION);

        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME,
                Map.of(AUTOCONFIG_EXCLUDE_PROPERTY, String.join(",", exclusions))));
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
