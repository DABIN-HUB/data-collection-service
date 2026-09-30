package com.wangbin.collector.monitor.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLoggerTest {

    private OperationLogger operationLogger;

    @BeforeEach
    void setUp() {
        operationLogger = new OperationLogger();
        operationLogger.setContext(new LoggerContext());
        operationLogger.start();
    }

    @AfterEach
    void tearDown() {
        operationLogger.stop();
    }

    @Test
    void shouldSanitizeSensitiveValuesAndFilterLogs() {
        operationLogger.doAppend(event(Level.INFO, "com.wangbin.collector.TestService",
                "连接参数 token=abc123 password:secret Bearer access-token"));
        operationLogger.doAppend(event(Level.ERROR, "com.wangbin.collector.OtherService", "设备连接失败"));

        List<OperationLogger.OperationLogEntry> entries = operationLogger.query("INFO", "testservice", "连接参数", 20);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message())
                .contains("token=***", "password:***", "Bearer ***")
                .doesNotContain("abc123", "access-token");
    }

    @Test
    void shouldReturnNewestEntriesFirstAndRespectLimit() {
        operationLogger.doAppend(event(Level.INFO, "test", "第一条"));
        operationLogger.doAppend(event(Level.INFO, "test", "第二条"));

        List<OperationLogger.OperationLogEntry> entries = operationLogger.query(null, null, null, 1);

        assertThat(entries).extracting(OperationLogger.OperationLogEntry::message)
                .containsExactly("第二条");
    }

    @Test
    void shouldCaptureRequestIdFromMdcAndSearchByRequestId() {
        operationLogger.doAppend(event(Level.INFO, "test", "普通运行日志", Map.of("requestId", "obs-052-001")));
        operationLogger.doAppend(event(Level.INFO, "test", "其它运行日志", Map.of("requestId", "other-request")));

        List<OperationLogger.OperationLogEntry> entries = operationLogger.query(null, null, "obs-052-001", 20);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).requestId()).isEqualTo("obs-052-001");
        assertThat(entries.get(0).message()).isEqualTo("普通运行日志");
    }

    private LoggingEvent event(Level level, String logger, String message) {
        return event(level, logger, message, Map.of());
    }

    private LoggingEvent event(Level level, String logger, String message, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName(logger);
        event.setThreadName("测试线程");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
