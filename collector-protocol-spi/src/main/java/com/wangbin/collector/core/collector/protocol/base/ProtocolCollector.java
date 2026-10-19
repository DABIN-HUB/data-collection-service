package com.wangbin.collector.core.collector.protocol.base;

/**
 * 协议采集器标记接口。
 *
 * 读取、写入和订阅等能力由更小的能力接口建模，例如 {@link ReadableCollector}、{@link WritableCollector} 和 {@link SubscribableCollector}。
 */
public interface ProtocolCollector extends CollectorLifecycle, CollectorMetadata {
    /** 自动调度恢复的可选策略；默认保留既有断开/连接行为，人工入口不受此策略限制。 */
    default boolean isAutomaticReconnectAllowed() {
        return true;
    }

    /** 执行时再次核对策略；调用者按既有流程断开并复核捕获实例的生命周期租约。 */
    default boolean reconnectAutomatically() {
        if (!isAutomaticReconnectAllowed()) return false;
        connect();
        return true;
    }
}
