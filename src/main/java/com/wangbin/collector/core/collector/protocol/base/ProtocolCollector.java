package com.wangbin.collector.core.collector.protocol.base;

/**
 * 协议采集器标记接口。
 *
 * 读取、写入和订阅等能力由更小的能力接口建模，例如 {@link ReadableCollector}、{@link WritableCollector} 和 {@link SubscribableCollector}。
 */
public interface ProtocolCollector extends CollectorLifecycle, CollectorMetadata {
}
