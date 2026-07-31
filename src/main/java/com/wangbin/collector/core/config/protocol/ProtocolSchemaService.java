package com.wangbin.collector.core.config.protocol;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes 界面 schema 元数据 derived from the 协议 descriptor registry.
 */
@Service
public class ProtocolSchemaService {

    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;
    private final Map<String, ProtocolSchema> schemas;
    private final Map<String, String> aliases;

    /**
     * 创建当前组件实例。
     */
    public ProtocolSchemaService() {
        this(new ProtocolDescriptorRegistry());
    }

    /**
     * 创建当前组件实例。
     */
    public ProtocolSchemaService(ProtocolDescriptorRegistry protocolDescriptorRegistry) {
        this.protocolDescriptorRegistry = protocolDescriptorRegistry;
        this.schemas = buildSchemas(protocolDescriptorRegistry);
        this.aliases = buildAliases(protocolDescriptorRegistry);
    }

    public List<ProtocolSchema> getAllSchemas() {
        return new ArrayList<>(schemas.values());
    }

    public Optional<ProtocolSchema> getSchema(String protocol) {
        String canonical = aliases.get(normalize(protocol));
        return canonical == null ? Optional.empty() : Optional.ofNullable(schemas.get(canonical));
    }

    public List<ProtocolFieldConfig> getConnectionFields(String protocol) {
        return getSchema(protocol)
                .map(ProtocolSchema::getConnectionFields)
                .orElseGet(Collections::emptyList);
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, ProtocolSchema> buildSchemas(ProtocolDescriptorRegistry registry) {
        LinkedHashMap<String, ProtocolSchema> built = new LinkedHashMap<>();
        for (ProtocolDescriptor descriptor : registry.primaryDescriptors()) {
            built.put(descriptor.code(), registry.toSchema(descriptor.code()));
        }
        return Collections.unmodifiableMap(built);
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, String> buildAliases(ProtocolDescriptorRegistry registry) {
        LinkedHashMap<String, String> built = new LinkedHashMap<>();
        for (String code : registry.allSupportedCodes()) {
            String canonical = registry.canonicalProtocol(code);
            if (canonical != null && schemas.containsKey(canonical)) {
                built.put(normalize(code), canonical);
            }
        }
        for (String protocol : schemas.keySet()) {
            built.putIfAbsent(normalize(protocol), protocol);
        }
        return Collections.unmodifiableMap(built);
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalize(String protocol) {
        if (protocol == null) {
            return "";
        }
        return protocol.trim().toUpperCase().replace("-", "_");
    }
}
