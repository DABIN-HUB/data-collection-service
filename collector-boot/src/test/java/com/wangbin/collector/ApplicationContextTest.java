package com.wangbin.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "collector.config.loader=file",
                "collector.cache.type=local",
                "telemetry.tdengine.enabled=false",
                "collector.report.mqtt.enabled=false"
        }
)
class ApplicationContextTest {

    @Test
    void contextShouldStart() {
    }
}
