package com.wangbin.collector.core.config.protocol;

/**
 * Describes which field acts as the primary 点位 type selector for a 协议.
 */
public enum ProtocolTypeMode {
    PLATFORM_ONLY,
    DRIVER_PRIMARY,
    PROTOCOL_FIELD_PRIMARY
}