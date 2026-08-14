package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 连接工厂，只负责解析连接类型、应用默认值、校验配置并委托 Provider 创建适配器。
 */
@Component
public class ConnectionFactory {

    private final ProtocolConnectionValidator protocolConnectionValidator;
    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;
    private final Map<String, ConnectionAdapterProvider> providers;

    public ConnectionFactory(ProtocolDescriptorRegistry protocolDescriptorRegistry,
                             ProtocolConnectionValidator protocolConnectionValidator,
                             List<ConnectionAdapterProvider> providers) {
        this.protocolDescriptorRegistry = Objects.requireNonNull(protocolDescriptorRegistry,
                "protocolDescriptorRegistry must not be null");
        this.protocolConnectionValidator = Objects.requireNonNull(protocolConnectionValidator,
                "protocolConnectionValidator must not be null");
        this.providers = buildProviderIndex(providers);
    }

    public ConnectionAdapter<?> createConnection(DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        if (deviceInfo == null || deviceInfo.getDeviceId() == null || deviceInfo.getDeviceId().isBlank()) {
            throw new IllegalArgumentException("设备信息无效");
        }
        DeviceConnection cfg = connectionConfig != null ? connectionConfig : new DeviceConnection();
        String connectionType = canonicalizeConnectionType(resolveConnectionType(deviceInfo, cfg), cfg);
        protocolConnectionValidator.validate(deviceInfo, cfg);

        ConnectionAdapterProvider provider = providers.get(connectionType);
        if (provider == null) {
            throw new CollectorException(
                    String.format("不支持的连接类型: %s", connectionType),
                    deviceInfo.getDeviceId(), null
            );
        }
        return provider.create(connectionType, deviceInfo, cfg);
    }

    Set<String> registeredConnectionTypes() {
        return providers.keySet();
    }

    ConnectionAdapterProvider providerFor(String connectionType) {
        return providers.get(normalize(connectionType));
    }

    private String resolveConnectionType(DeviceInfo deviceInfo, DeviceConnection cfg) {
        String protocolType = null;
        if (deviceInfo.getProtocolType() != null && !deviceInfo.getProtocolType().isBlank()) {
            protocolType = normalize(deviceInfo.getProtocolType());
            if (protocolDescriptorRegistry.resolve(protocolType) != null) {
                return protocolType;
            }
        }
        if (deviceInfo.getConnectionType() != null && !deviceInfo.getConnectionType().isBlank()) {
            return normalize(deviceInfo.getConnectionType());
        }
        if (cfg != null && cfg.getConnectionType() != null && !cfg.getConnectionType().isBlank()) {
            return normalize(cfg.getConnectionType());
        }
        if (protocolType != null) {
            return protocolType;
        }
        return "TCP";
    }

    private String canonicalizeConnectionType(String type, DeviceConnection cfg) {
        return protocolDescriptorRegistry.applyConnectionDefaults(type, cfg);
    }

    private Map<String, ConnectionAdapterProvider> buildProviderIndex(List<ConnectionAdapterProvider> providerList) {
        if (providerList == null || providerList.isEmpty()) {
            throw new IllegalStateException("未发现 ConnectionAdapterProvider Bean");
        }
        List<ConnectionAdapterProvider> orderedProviders = new ArrayList<>(providerList);
        AnnotationAwareOrderComparator.sort(orderedProviders);

        Map<String, ConnectionAdapterProvider> indexedProviders = new LinkedHashMap<>();
        for (ConnectionAdapterProvider provider : orderedProviders) {
            if (provider == null) {
                throw new IllegalStateException("ConnectionAdapterProvider 不能为空");
            }
            registerProvider(indexedProviders, provider);
        }
        return Collections.unmodifiableMap(indexedProviders);
    }

    private void registerProvider(Map<String, ConnectionAdapterProvider> indexedProviders,
                                  ConnectionAdapterProvider provider) {
        Set<String> rawTypes = provider.supportedConnectionTypes();
        if (rawTypes == null || rawTypes.isEmpty()) {
            throw new IllegalStateException("ConnectionAdapterProvider 未声明 connectionType: "
                    + provider.getClass().getName());
        }
        Set<String> normalizedByProvider = new LinkedHashSet<>();
        for (String rawType : rawTypes) {
            String connectionType = normalizeRequired(rawType, provider);
            if (!normalizedByProvider.add(connectionType)) {
                throw new IllegalStateException("ConnectionAdapterProvider 内部重复 connectionType: "
                        + connectionType + ", provider=" + provider.getClass().getName());
            }
            ConnectionAdapterProvider existing = indexedProviders.putIfAbsent(connectionType, provider);
            if (existing != null) {
                throw new IllegalStateException("重复 connectionType Provider: " + connectionType
                        + ", provider=" + provider.getClass().getName()
                        + ", existingProvider=" + existing.getClass().getName());
            }
        }
    }

    private String normalizeRequired(String connectionType, ConnectionAdapterProvider provider) {
        if (connectionType == null || connectionType.isBlank()) {
            throw new IllegalStateException("ConnectionAdapterProvider 返回空 connectionType: "
                    + provider.getClass().getName());
        }
        return normalize(connectionType);
    }

    private String normalize(String type) {
        if (type == null) {
            return "";
        }
        return type.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }
}
