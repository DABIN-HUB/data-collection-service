package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;

import java.util.List;

public record ProtocolDescriptor(String code,
                                 List<String> aliases,
                                 Class<? extends ProtocolCollector> collectorClass,
                                 String connectionType,
                                 Integer defaultPort,
                                 ProtocolAddressingMode addressingMode) {
}
