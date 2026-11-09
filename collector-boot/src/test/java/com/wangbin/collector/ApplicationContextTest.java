package com.wangbin.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "collector.config.loader=file",
                "collector.cache.type=local",
                "collector.telemetry-ingress-buffer.enabled=false",
                "collector.report.shadow.persistence-enabled=false",
                "telemetry.tdengine.enabled=false",
                "collector.report.mqtt.enabled=false"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationContextTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("wangbin.config.sqlite.database-path",
                () -> directory.resolve("sqlite/collector.db").toString());
    }

    @Test
    void contextShouldStart() {
        Path database = directory.resolve("sqlite/collector.db");
        if (!Files.isRegularFile(database)) {
            throw new AssertionError("测试专用 SQLite 数据库未由应用初始化: " + database);
        }
        if (!Files.isDirectory(directory.resolve("sqlite.wangbin-installation"))) {
            throw new AssertionError("测试专用 SQLite 安装保护目录未创建: " + directory);
        }
    }
}
