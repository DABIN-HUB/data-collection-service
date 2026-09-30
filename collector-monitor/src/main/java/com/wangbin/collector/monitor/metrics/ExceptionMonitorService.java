package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.port.ExceptionReporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 异常&错误监控服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionMonitorService implements ExceptionReporter {

    static final int MAX_RECENT = 100;
    static final int MAX_DEVICE_COUNTER_KEYS = 4096;
    static final int MAX_CATEGORY_COUNTER_KEYS = 64;
    static final int MAX_EXCEPTION_MESSAGE_LENGTH = 1024;
    static final int MAX_CONTEXT_ID_LENGTH = 256;
    static final int MAX_REQUEST_ID_LENGTH = 128;

    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|deviceKey|accessKey|authorization|credential|api[-_]?key|signature)\\b\\s*([:=])\\s*(Bearer\\s+)?[^\\s,;]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");

    private final LongAdder totalCounter = new LongAdder();
    private final Map<String, LongAdder> categoryCounter = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> deviceCounter = new ConcurrentHashMap<>();
    private final LongAdder otherCategoryExceptions = new LongAdder();
    private final LongAdder otherDeviceExceptions = new LongAdder();
    private final Object categoryCounterLock = new Object();
    private final Object deviceCounterLock = new Object();
    private final ArrayDeque<ExceptionSummary> recent = new ArrayDeque<>(MAX_RECENT);

    /**
     * 记录或统计业务状态。
     */
    @Override
    public void record(Throwable throwable, String deviceId, String pointId) {
        String category = categorize(throwable);
        String trackedDeviceId = sanitizeContextId(deviceId, MAX_CONTEXT_ID_LENGTH);
        totalCounter.increment();
        incrementBounded(categoryCounter, category, MAX_CATEGORY_COUNTER_KEYS, otherCategoryExceptions, categoryCounterLock);
        if (!trackedDeviceId.isEmpty()) {
            incrementBounded(deviceCounter, trackedDeviceId, MAX_DEVICE_COUNTER_KEYS, otherDeviceExceptions, deviceCounterLock);
        }

        ExceptionSummary summary = ExceptionSummary.builder()
                .deviceId(trackedDeviceId)
                .pointId(sanitizeContextId(pointId, MAX_CONTEXT_ID_LENGTH))
                .category(category)
                .exceptionType(exceptionType(throwable))
                .message(safeMessage(throwable))
                .requestId(sanitizeContextId(MDC.get("requestId"), MAX_REQUEST_ID_LENGTH))
                .timestamp(Instant.now().toEpochMilli())
                .build();

        synchronized (recent) {
            if (recent.size() >= MAX_RECENT) {
                recent.removeFirst();
            }
            recent.addLast(summary);
        }
    }

    public ExceptionStatsSnapshot getStats() {
        Map<String, Long> categoryStats = categoryCounter.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().longValue()));

        Map<String, Long> deviceStats = deviceCounter.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().longValue()));

        List<ExceptionSummary> recentCopy;
        synchronized (recent) {
            recentCopy = new ArrayList<>(recent);
        }

        return ExceptionStatsSnapshot.builder()
                .totalExceptions(totalCounter.longValue())
                .byCategory(categoryStats)
                .byDevice(deviceStats)
                .recent(Collections.unmodifiableList(recentCopy))
                .trackedCategoryCount(categoryCounter.size())
                .categoryCapacity(MAX_CATEGORY_COUNTER_KEYS)
                .otherCategoryExceptions(otherCategoryExceptions.longValue())
                .trackedDeviceCount(deviceCounter.size())
                .deviceCapacity(MAX_DEVICE_COUNTER_KEYS)
                .otherDeviceExceptions(otherDeviceExceptions.longValue())
                .build();
    }

    private void incrementBounded(Map<String, LongAdder> counters,
                                  String key,
                                  int capacity,
                                  LongAdder overflowCounter,
                                  Object lock) {
        LongAdder existing = counters.get(key);
        if (existing != null) {
            existing.increment();
            return;
        }

        synchronized (lock) {
            existing = counters.get(key);
            if (existing != null) {
                existing.increment();
                return;
            }
            if (counters.size() >= capacity) {
                overflowCounter.increment();
                return;
            }
            LongAdder created = new LongAdder();
            created.increment();
            counters.put(key, created);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String categorize(Throwable throwable) {
        if (throwable == null) {
            return "UNKNOWN";
        }
        if (throwable instanceof ConnectException) {
            return "CONNECTION";
        }
        String message = throwable.getMessage();
        String lowerMessage = message != null ? message.toLowerCase() : "";
        if (throwable instanceof SocketTimeoutException || lowerMessage.contains("timeout")) {
            return "TIMEOUT";
        }
        if (lowerMessage.contains("parse")) {
            return "PARSE";
        }
        if (lowerMessage.contains("auth")) {
            return "AUTH";
        }
        String simpleName = throwable.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank() ? "UNKNOWN" : simpleName.toUpperCase();
    }

    private String exceptionType(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String simpleName = throwable.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank() ? throwable.getClass().getName() : simpleName;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return "";
        }
        String sanitized = SECRET_ASSIGNMENT_PATTERN.matcher(throwable.getMessage()).replaceAll("$1$2***");
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer ***");
        if (sanitized.length() <= MAX_EXCEPTION_MESSAGE_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH);
    }

    private String sanitizeContextId(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
