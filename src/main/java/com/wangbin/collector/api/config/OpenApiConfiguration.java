package com.wangbin.collector.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理接口OpenAPI配置。
 */
@Configuration
public class OpenApiConfiguration {

    public static final String TOKEN_SECURITY_SCHEME = "采集服务令牌";

    /**
     * 执行当前业务逻辑。
     */
    @Bean
    public OpenAPI collectorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("工业数据采集服务接口")
                        .description("设备配置、采集控制、实时数据和运行监控接口")
                        .version("v1"))
                .schemaRequirement(TOKEN_SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Collector-Token"));
    }
}
