package com.wangbin.collector.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigurationTest {

    @Test
    void shouldExposeChineseApiMetadataAndTokenScheme() {
        OpenAPI openAPI = new OpenApiConfiguration().collectorOpenApi();

        assertThat(openAPI.getInfo().getTitle()).isEqualTo("工业数据采集服务接口");
        assertThat(openAPI.getComponents().getSecuritySchemes())
                .containsKey(OpenApiConfiguration.TOKEN_SECURITY_SCHEME);
    }
}
