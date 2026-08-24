package com.wangbin.collector.core.config.protocol;

/**
 * 协议元数据提供者。
 *
 * <p>每个协议或协议族可以独立提供元数据，避免所有协议字段持续堆积在注册表构造器中。</p>
 */
public interface ProtocolDescriptorProvider {

    /**
     * 向协议注册表注册元数据。
     *
     * @param registry 协议元数据注册表
     */
    void register(ProtocolDescriptorRegistry registry);
}
