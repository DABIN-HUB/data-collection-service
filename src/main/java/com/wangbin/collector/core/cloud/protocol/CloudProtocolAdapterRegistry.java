package com.wangbin.collector.core.cloud.protocol;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 云平台协议适配器注册表，按 cloud-provider 选择具体实现。
 */
@Component
public class CloudProtocolAdapterRegistry {

    private final Map<String, CloudProtocolAdapter> adapters = new LinkedHashMap<>();

    /**
     * 创建当前组件实例。
     */
    public CloudProtocolAdapterRegistry(List<CloudProtocolAdapter> adapters) {
        if (adapters != null) {
            for (CloudProtocolAdapter adapter : adapters) {
                register(adapter);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    public CloudProtocolAdapter resolve(String provider) {
        String key = normalize(provider);
        CloudProtocolAdapter adapter = adapters.get(key);
        if (adapter == null) {
            throw new IllegalArgumentException("unsupported cloud protocol provider: " + key);
        }
        return adapter;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean supports(String provider) {
        return adapters.containsKey(normalize(provider));
    }

    /**
     * 执行当前业务逻辑。
     */
    public Set<String> providerKeys() {
        return adapters.keySet();
    }

    /**
     * 执行当前业务逻辑。
     */
    public Collection<CloudProtocolAdapter> adapters() {
        return adapters.values();
    }

    /**
     * 维护注册或订阅关系。
     */
    private void register(CloudProtocolAdapter adapter) {
        if (adapter == null) {
            return;
        }
        adapters.put(normalize(adapter.provider()), adapter);
        for (String alias : adapter.aliases()) {
            adapters.put(normalize(alias), adapter);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            return CloudProtocolAdapter.DEFAULT_PROVIDER;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
