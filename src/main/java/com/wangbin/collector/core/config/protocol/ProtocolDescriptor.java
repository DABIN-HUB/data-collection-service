package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;

import java.util.Collections;
import java.util.List;

public record ProtocolDescriptor(String code,
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

    public ProtocolDescriptor {
        aliases = aliases == null ? Collections.emptyList() : List.copyOf(aliases);
        connectionFields = connectionFields == null ? Collections.emptyList() : List.copyOf(connectionFields);
        pointAddressHints = pointAddressHints == null ? Collections.emptyList() : List.copyOf(pointAddressHints);
    }
}
