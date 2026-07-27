package com.wangbin.collector.core.collector.protocol.iec101.domain;

/**
 * IEC101 信息对象。
 */
public record Iec101InformationObject(int address,
                                      Object value,
                                      int quality,
                                      int rawQuality,
                                      Long sourceTimestamp) {
}
