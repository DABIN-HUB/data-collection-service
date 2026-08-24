package com.wangbin.collector.monitor.network;

import java.util.List;

/**
 * 网络检测结果。
 */
public record NetworkDiagnosticResult(NetworkDiagnosticType type,
                                      String deviceId,
                                      String target,
                                      String resolvedAddress,
                                      Integer port,
                                      boolean reachable,
                                      long durationMs,
                                      String message,
                                      List<String> details,
                                      long completedAt) {
}