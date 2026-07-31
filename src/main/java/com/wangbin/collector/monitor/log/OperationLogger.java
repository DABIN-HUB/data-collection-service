package com.wangbin.collector.monitor.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 控制台运行日志缓冲器。
 *
 * <p>仅在内存中保留最近日志，并在入队前完成敏感字段脱敏。</p>
 */
@Component
public class OperationLogger extends AppenderBase<ILoggingEvent> {

    private static final int MAX_ENTRY_COUNT = 2_000;
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int DEFAULT_QUERY_LIMIT = 200;
    private static final int MAX_QUERY_LIMIT = 1_000;
    private static final Pattern SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|deviceKey|accessKey|authorization)(\\s*[:=]\\s*)([^\\s,;\\]}]+)");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private final Deque<OperationLogEntry> entries = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private Logger rootLogger;

    /**
     * 注册到根记录器，采集应用运行日志。
     */
    @PostConstruct
    public void initialize() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(context);
        setName("collector-console-operation-log");
        start();
        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(this);
    }

    /**
     * 从根记录器移除，避免应用关闭时残留引用。
     */
    @PreDestroy
    public void destroy() {
        if (rootLogger != null) {
            rootLogger.detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        OperationLogEntry entry = new OperationLogEntry(
                event.getTimeStamp(),
                event.getLevel() == null ? Level.INFO.levelStr : event.getLevel().levelStr,
                safeText(event.getLoggerName()),
                safeText(event.getThreadName()),
                sanitize(event.getFormattedMessage()));
        lock.lock();
        try {
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRY_COUNT) {
                entries.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按条件查询最近日志，结果按照时间倒序返回。
     *
     * @param level 日志级别
     * @param loggerName 记录器名称片段
     * @param keyword 日志内容关键字
     * @param limit 返回数量上限
     * @return 日志快照
     */
    public List<OperationLogEntry> query(String level, String loggerName, String keyword, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        String normalizedLevel = normalize(level);
        String normalizedLogger = normalize(loggerName);
        String normalizedKeyword = normalize(keyword);
        List<OperationLogEntry> result = new ArrayList<>(safeLimit);
        lock.lock();
        try {
            var iterator = entries.descendingIterator();
            while (iterator.hasNext() && result.size() < safeLimit) {
                OperationLogEntry entry = iterator.next();
                if (matches(entry, normalizedLevel, normalizedLogger, normalizedKeyword)) {
                    result.add(entry);
                }
            }
        } finally {
            lock.unlock();
        }
        return List.copyOf(result);
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private boolean matches(OperationLogEntry entry,
                            String level,
                            String loggerName,
                            String keyword) {
        if (StringUtils.hasText(level) && !entry.level().equalsIgnoreCase(level)) {
            return false;
        }
        if (StringUtils.hasText(loggerName) && !normalize(entry.logger()).contains(loggerName)) {
            return false;
        }
        return !StringUtils.hasText(keyword)
                || normalize(entry.message()).contains(keyword)
                || normalize(entry.logger()).contains(keyword)
                || normalize(entry.thread()).contains(keyword);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_QUERY_LIMIT;
        }
        return Math.max(1, Math.min(MAX_QUERY_LIMIT, limit));
    }

    private String sanitize(String message) {
        String safeMessage = safeText(message);
        safeMessage = SENSITIVE_FIELD_PATTERN.matcher(safeMessage).replaceAll("$1$2***");
        safeMessage = BEARER_PATTERN.matcher(safeMessage).replaceAll("$1***");
        if (safeMessage.length() <= MAX_MESSAGE_LENGTH) {
            return safeMessage;
        }
        return safeMessage.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private String normalize(String value) {
        return safeText(value).trim().toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 控制台日志条目。
     */
    public record OperationLogEntry(long timestamp,
                                    String level,
                                    String logger,
                                    String thread,
                                    String message) {
    }
}