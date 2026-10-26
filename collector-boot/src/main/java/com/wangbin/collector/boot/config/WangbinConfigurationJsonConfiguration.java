package com.wangbin.collector.boot.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置 HTTP JSON 的无损边界，不修改运行期实体 getter。 */
@Configuration(proxyBeanMethods = false)
public class WangbinConfigurationJsonConfiguration {
    @Bean
    public SimpleModule wangbinConfigurationJsonModule() {
        return new WangbinConfigurationJsonModule();
    }
}
