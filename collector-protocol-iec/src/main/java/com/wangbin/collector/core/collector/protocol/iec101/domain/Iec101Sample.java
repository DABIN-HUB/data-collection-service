package com.wangbin.collector.core.collector.protocol.iec101.domain;

/**
 * IEC101 点位采样值。
 */
public record Iec101Sample(int commonAddress,
                           int typeId,
                           int informationObjectAddress,
                           Object value,
                           int quality,
                           int rawQuality,
                           Long sourceTimestamp) {
}
