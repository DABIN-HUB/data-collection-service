package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 协议描述注册表，只负责收集 Provider、校验冲突并建立查询索引。
 */
@Component
public class ProtocolDescriptorRegistry {

    private static final Set<String> TOP_LEVEL_CONNECTION_FIELDS = new HashSet<>(List.of(
            "connectionType", "host", "port", "url", "connectTimeout", "readTimeout", "writeTimeout",
            "timeout", "heartbeatInterval", "heartbeatTimeout", "subscriptionInterval", "reconnectDelay",
            "username", "password", "clientId", "productKey", "deviceSecret", "authToken",
            "sslEnabled", "sslCertPath", "sslKeyPath", "keepAlive", "bufferSize",
            "autoReconnect", "maxPendingMessages", "dispatchBatchSize", "dispatchFlushInterval",
            "overflowStrategy", "securityPolicy", "authParams"
    ));

    private final Map<String, ProtocolDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, String> descriptorOwners = new LinkedHashMap<>();
    private final Map<String, AliasDescriptor> aliases = new LinkedHashMap<>();
    private String currentProviderName = "direct";

    /**
     * Spring 注入全部协议元数据 Provider；Registry 不持有任何具体协议知识。
     *
     * @param providers 协议元数据提供者列表
     */
    public ProtocolDescriptorRegistry(List<ProtocolDescriptorProvider> providers) {
        registerProviders(providers);
    }

    public boolean supports(String protocol) {
        return canonicalProtocol(protocol) != null;
    }

    public String canonicalProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        String normalized = normalize(protocol);
        if (descriptors.containsKey(normalized)) {
            return normalized;
        }
        AliasDescriptor alias = aliases.get(normalized);
        return alias != null ? alias.primaryCode() : null;
    }

    public ProtocolDescriptor resolve(String protocol) {
        String canonical = canonicalProtocol(protocol);
        return canonical == null ? null : descriptors.get(canonical);
    }

    public Collection<ProtocolDescriptor> primaryDescriptors() {
        return List.copyOf(descriptors.values());
    }

    public Collection<ProtocolDescriptor> allDescriptorsIncludingAliases() {
        LinkedHashMap<String, ProtocolDescriptor> resolved = new LinkedHashMap<>();
        for (ProtocolDescriptor descriptor : descriptors.values()) {
            resolved.put(descriptor.code(), descriptor);
        }
        for (Map.Entry<String, AliasDescriptor> entry : aliases.entrySet()) {
            ProtocolDescriptor primary = descriptors.get(entry.getValue().primaryCode());
            if (primary != null) {
                resolved.putIfAbsent(entry.getKey(), primary);
            }
        }
        return List.copyOf(resolved.values());
    }

    public Collection<String> allSupportedCodes() {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(descriptors.keySet());
        codes.addAll(aliases.keySet());
        return Collections.unmodifiableSet(codes);
    }

    public String applyConnectionDefaults(String protocol, DeviceConnection cfg) {
        ProtocolDescriptor descriptor = resolve(protocol);
        String canonical = descriptor != null ? descriptor.connectionType() : normalize(protocol);
        AliasDescriptor alias = aliases.get(normalize(protocol));
        if (alias != null && alias.customizer() != null) {
            alias.customizer().accept(cfg);
        }
        if (descriptor != null) {
            applyDefaultPort(cfg, descriptor.defaultPort());
        }
        return canonical;
    }

    public ProtocolSchema toSchema(String protocol) {
        ProtocolDescriptor descriptor = resolve(protocol);
        if (descriptor == null) {
            return null;
        }
        return ProtocolSchema.builder()
                .protocol(descriptor.code())
                .title(descriptor.title())
                .description(descriptor.description())
                .implemented(descriptor.implemented())
                .writable(descriptor.writable())
                .subscribable(descriptor.subscribable())
                .implementationState(descriptor.implementationState())
                .writeCapability(descriptor.writeCapability())
                .subscriptionCapability(descriptor.subscriptionCapability())
                .browseCapability(descriptor.browseCapability())
                .aliases(descriptor.aliases())
                .pointAddressHints(descriptor.pointAddressHints())
                .dataTypes(descriptor.dataTypes())
                .typeMode(descriptor.typeMode())
                .primaryTypeField(descriptor.primaryTypeField())
                .platformDataTypeMode(descriptor.platformDataTypeMode())
                .driverTypeEnabled(descriptor.driverTypeEnabled())
                .driverTypeLabel(descriptor.driverTypeLabel())
                .driverTypeField(descriptor.driverTypeField())
                .driverDataTypes(descriptor.driverDataTypes())
                .pointFields(descriptor.pointFields())
                .connectionFields(resolveConnectionFields(descriptor))
                .build();
    }

    private List<ProtocolFieldConfig> resolveConnectionFields(ProtocolDescriptor descriptor) {
        if (!descriptor.subscribable()) {
            return descriptor.connectionFields();
        }
        boolean alreadyConfigured = descriptor.connectionFields().stream()
                .anyMatch(item -> "subscriptionFallbackStrategy".equals(item.getName()));
        if (alreadyConfigured) {
            return descriptor.connectionFields();
        }
        List<ProtocolFieldConfig> resolved = new java.util.ArrayList<>(descriptor.connectionFields());
        resolved.add(field("subscriptionFallbackStrategy", "select", "订阅不可用处理策略", false,
                "FAIL_FAST", List.of("FAIL_FAST", "FALLBACK_TO_POLLING"), "advanced",
                "驱动或设备不支持订阅时，可选择立即失败或继续使用现有轮询采集。"));
        return List.copyOf(resolved);
    }

    private void registerProviders(List<ProtocolDescriptorProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return;
        }
        List<ProtocolDescriptorProvider> orderedProviders = new java.util.ArrayList<>(providers);
        AnnotationAwareOrderComparator.sort(orderedProviders);
        for (ProtocolDescriptorProvider provider : orderedProviders) {
            if (provider == null) {
                continue;
            }
            String previous = currentProviderName;
            currentProviderName = provider.getClass().getName();
            try {
                provider.register(this);
            } finally {
                currentProviderName = previous;
            }
        }
    }

    /**
     * Provider 使用该方法注册主协议；重复 code、alias 与主 code 冲突会在启动时失败。
     *
     * @param descriptor 协议元数据描述
     */
    void registerPrimary(ProtocolDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Protocol descriptor must not be null, provider=" + currentProviderName);
        }
        String code = normalizeRequired(descriptor.code(), "protocol code");
        if (descriptors.containsKey(code)) {
            throw new IllegalStateException("Duplicate protocol code " + code + ", provider="
                    + currentProviderName + ", existingProvider=" + descriptorOwners.get(code));
        }
        AliasDescriptor conflictingAlias = aliases.get(code);
        if (conflictingAlias != null) {
            throw new IllegalStateException("Protocol code conflicts with alias " + code + ", provider="
                    + currentProviderName + ", aliasProvider=" + conflictingAlias.owner());
        }
        descriptors.put(code, descriptor);
        descriptorOwners.put(code, currentProviderName);
        for (String alias : descriptor.aliases()) {
            registerAlias(alias, code, null);
        }
    }

    /**
     * Provider 使用该方法为已有 alias 追加连接默认值，不能跨主协议重复注册 alias。
     *
     * @param alias alias 协议编码
     * @param primaryCode 主协议编码
     * @param customizer 连接默认值修正逻辑
     */
    void registerAlias(String alias, String primaryCode, Consumer<DeviceConnection> customizer) {
        String normalizedAlias = normalizeRequired(alias, "protocol alias");
        String normalizedPrimary = normalizeRequired(primaryCode, "primary protocol code");
        if (!descriptors.containsKey(normalizedPrimary)) {
            throw new IllegalStateException("Alias " + normalizedAlias + " references unknown primary code "
                    + normalizedPrimary + ", provider=" + currentProviderName);
        }
        if (normalizedAlias.equals(normalizedPrimary)) {
            return;
        }
        if (descriptors.containsKey(normalizedAlias)) {
            throw new IllegalStateException("Alias conflicts with primary protocol code " + normalizedAlias
                    + ", provider=" + currentProviderName + ", primaryProvider="
                    + descriptorOwners.get(normalizedAlias));
        }
        AliasDescriptor existing = aliases.get(normalizedAlias);
        if (existing != null) {
            if (existing.primaryCode().equals(normalizedPrimary)
                    && existing.customizer() == null
                    && customizer != null) {
                aliases.put(normalizedAlias, new AliasDescriptor(normalizedPrimary, customizer, currentProviderName));
                return;
            }
            throw new IllegalStateException("Duplicate protocol alias " + normalizedAlias + ", provider="
                    + currentProviderName + ", existingProvider=" + existing.owner()
                    + ", primary=" + normalizedPrimary + ", existingPrimary=" + existing.primaryCode());
        }
        aliases.put(normalizedAlias, new AliasDescriptor(normalizedPrimary, customizer, currentProviderName));
    }

    ProtocolDescriptor descriptor(String code,
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
                                  List<String> pointAddressHints,
                                  List<ProtocolFieldConfig> connectionFields) {
        return new ProtocolDescriptor(code, title, description, aliases, collectorClass, connectionType, defaultPort,
                addressingMode, implemented, writable, subscribable, connectionFields, pointAddressHints);
    }

    ProtocolDescriptor descriptor(String code,
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
                                  List<String> pointAddressHints,
                                  List<ProtocolFieldConfig> connectionFields) {
        return new ProtocolDescriptor(code, title, description, aliases, collectorClass, connectionType, defaultPort,
                addressingMode, implementationState, writeCapability, subscriptionCapability, browseCapability,
                connectionFields, pointAddressHints);
    }

    List<ProtocolFieldConfig> fields(ProtocolFieldConfig... fields) {
        return Arrays.asList(fields);
    }

    ProtocolFieldConfig field(String name,
                              String type,
                              String label,
                              boolean required,
                              String defaultValue,
                              List<String> options,
                              String group) {
        return conditional(name, type, label, required, defaultValue, options, group, null, null);
    }

    ProtocolFieldConfig field(String name,
                              String type,
                              String label,
                              boolean required,
                              String defaultValue,
                              List<String> options,
                              String group,
                              String description) {
        return conditional(name, type, label, required, defaultValue, options, group, null, description);
    }

    ProtocolFieldConfig conditional(String name,
                                    String type,
                                    String label,
                                    boolean required,
                                    String defaultValue,
                                    List<String> options,
                                    String group,
                                    String requiredWhen) {
        return conditional(name, type, label, required, defaultValue, options, group, requiredWhen, null);
    }

    ProtocolFieldConfig conditional(String name,
                                    String type,
                                    String label,
                                    boolean required,
                                    String defaultValue,
                                    List<String> options,
                                    String group,
                                    String requiredWhen,
                                    String description) {
        return ProtocolFieldConfig.builder()
                .name(name)
                .type(type)
                .label(label)
                .required(required)
                .defaultValue(defaultValue)
                .description(description)
                .options(options == null ? Collections.emptyList() : options)
                .group(group)
                .requiredWhen(requiredWhen)
                .storage(resolveStorage(name))
                .build();
    }

    ProtocolFieldConfig pointField(String name,
                                   String type,
                                   String label,
                                   boolean required,
                                   String defaultValue,
                                   List<String> options,
                                   String description,
                                   String requiredWhen) {
        return ProtocolFieldConfig.builder()
                .name(name)
                .type(type)
                .label(label)
                .required(required)
                .defaultValue(defaultValue)
                .description(description)
                .group("protocol")
                .requiredWhen(requiredWhen)
                .storage(resolvePointStorage(name))
                .options(options == null ? Collections.emptyList() : options)
                .build();
    }

    static void applyDefaultPort(DeviceConnection cfg, Integer defaultPort) {
        if (cfg != null && cfg.getPort() == null && defaultPort != null && defaultPort > 0) {
            cfg.setPort(defaultPort);
        }
    }

    static void putExtIfAbsent(DeviceConnection cfg, String key, Object value) {
        if (cfg == null) {
            return;
        }
        if (cfg.getExtJson() == null) {
            cfg.setExtJson(new LinkedHashMap<>());
        }
        cfg.getExtJson().putIfAbsent(key, value);
    }

    private String resolveStorage(String name) {
        return TOP_LEVEL_CONNECTION_FIELDS.contains(name) ? "topLevel" : "extJson";
    }

    private String resolvePointStorage(String name) {
        return name != null && name.startsWith("additionalConfig.") ? "extJson" : "topLevel";
    }

    private String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank, provider=" + currentProviderName);
        }
        return normalize(value);
    }

    private String normalize(String protocol) {
        if (protocol == null) {
            return "";
        }
        return protocol.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    private record AliasDescriptor(String primaryCode,
                                   Consumer<DeviceConnection> customizer,
                                   String owner) {
    }
}
