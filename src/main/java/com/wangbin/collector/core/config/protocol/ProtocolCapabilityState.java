package com.wangbin.collector.core.config.protocol;

/**
 * 协议能力状态。
 */
public enum ProtocolCapabilityState {

    SUPPORTED("支持", true),
    UNSUPPORTED("不支持", false),
    RUNTIME_DEPENDENT("依赖运行环境", true),
    EXPERIMENTAL("实验性", true);

    private final String description;
    private final boolean available;

    /**
     * 创建当前组件实例。
     */
    ProtocolCapabilityState(String description, boolean available) {
        this.description = description;
        this.available = available;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAvailable() {
        return available;
    }
}
