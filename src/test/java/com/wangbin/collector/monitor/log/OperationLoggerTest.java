package com.wangbin.collector.monitor.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private LoggingEvent event(Level level, String logger, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName(logger);
        event.setThreadName("测试线程");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}