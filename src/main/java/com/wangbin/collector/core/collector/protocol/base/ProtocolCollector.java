package com.wangbin.collector.core.collector.protocol.base;

/**
 * Marker interface for protocol collectors.
 *
 * Operational abilities are modeled by smaller capability interfaces such as
 * {@link ReadableCollector}, {@link WritableCollector}, and
 * {@link SubscribableCollector}.
 */
public interface ProtocolCollector extends CollectorLifecycle, CollectorMetadata {
}
