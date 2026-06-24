package com.wangbin.collector.core.config.protocol;

/**
 * Describes which field acts as the primary point type selector for a protocol.
 */
public enum ProtocolTypeMode {
    PLATFORM_ONLY,
    DRIVER_PRIMARY,
    PROTOCOL_FIELD_PRIMARY
}