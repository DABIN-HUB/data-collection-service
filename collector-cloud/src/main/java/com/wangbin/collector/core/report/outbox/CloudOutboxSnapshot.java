package com.wangbin.collector.core.report.outbox;

/**
 * 云端发件箱轻量监控快照。
 */
public record CloudOutboxSnapshot(boolean enabled,
                                  long pending,
                                  long isolated,
                                  long oldestMessageAgeMillis) {
}
