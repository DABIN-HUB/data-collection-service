package com.wangbin.collector.core.collector.edge;

/**
 * 边缘进程上送的单点遥测输入。
 */
public record EdgeTelemetrySample(String deviceId,
                                  String pointRef,
                                  Object value,
                                  Integer quality,
                                  Long timestamp,
                                  long sequence) {
}
