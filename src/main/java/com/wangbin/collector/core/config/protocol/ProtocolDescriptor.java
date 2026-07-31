package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;

import java.util.Collections;
import java.util.List;

/**
 * 定义当前模块的不可变数据记录。
 */
public record ProtocolDescriptor(String code,
                                 String title,
                                 String description,
                                 List<String> aliases,
                                 Class<? extends ProtocolCollector> collectorClass,
                                 String connectionType,
                                 Integer defaultPort,
                                 ProtocolAddressingMode addressingMode,
                                 ProtocolCapabilityState implementationState,
                                 ProtocolCapabilityState writeCapability,
                                 ProtocolCapabilityState subscriptionCapability,
                                 ProtocolCapabilityState browseCapability,
                                 List<ProtocolFieldConfig> connectionFields,
                                 List<String> pointAddressHints) {

    public ProtocolDescriptor {
        aliases = aliases == null ? Collections.emptyList() : List.copyOf(aliases);
        implementationState = defaultState(implementationState);
        writeCapability = defaultState(writeCapability);
        subscriptionCapability = defaultState(subscriptionCapability);
        browseCapability = defaultState(browseCapability);
        connectionFields = connectionFields == null ? Collections.emptyList() : List.copyOf(connectionFields);
        pointAddressHints = pointAddressHints == null ? Collections.emptyList() : List.copyOf(pointAddressHints);
    }

    /**
     * 创建当前组件实例。
     */
    public ProtocolDescriptor(String code,
                              String title,
                              String description,
                              List<String> aliases,
                              Class<? extends ProtocolCollector> collectorClass,
                              String connectionType,
                              Integer defaultPort,
                              ProtocolAddressingMode addressingMode,
                              boolean implemented,
                              boolean writable,
                              boolean subscribable,
                              List<ProtocolFieldConfig> connectionFields,
                              List<String> pointAddressHints) {
        this(code, title, description, aliases, collectorClass, connectionType, defaultPort, addressingMode,
                stateOf(implemented), stateOf(writable), stateOf(subscribable),
                ProtocolCapabilityState.UNSUPPORTED, connectionFields, pointAddressHints);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean implemented() {
        return implementationState.isAvailable();
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean writable() {
        return writeCapability.isAvailable();
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean subscribable() {
        return subscriptionCapability.isAvailable();
    }

    /**
     * 执行当前业务逻辑。
     */
    private static ProtocolCapabilityState stateOf(boolean available) {
        return available ? ProtocolCapabilityState.SUPPORTED : ProtocolCapabilityState.UNSUPPORTED;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static ProtocolCapabilityState defaultState(ProtocolCapabilityState state) {
        return state == null ? ProtocolCapabilityState.UNSUPPORTED : state;
    }
}
