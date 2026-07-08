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

    public CloudProtocolAdapterRegistry(List<CloudProtocolAdapter> adapters) {
        if (adapters != null) {
            for (CloudProtocolAdapter adapter : adapters) {
                register(adapter);
            }
        }
    }

    public CloudProtocolAdapter resolve(String provider) {
        String key = normalize(provider);
        CloudProtocolAdapter adapter = adapters.get(key);
        if (adapter == null) {
            throw new IllegalArgumentException("unsupported cloud protocol provider: " + key);
        }
        return adapter;
    }

    public boolean supports(String provider) {
        return adapters.containsKey(normalize(provider));
    }

    public Set<String> providerKeys() {
        return adapters.keySet();
    }

    public Collection<CloudProtocolAdapter> adapters() {
        return adapters.values();
    }

    private void register(CloudProtocolAdapter adapter) {
        if (adapter == null) {
            return;
        }
        adapters.put(normalize(adapter.provider()), adapter);
        for (String alias : adapter.aliases()) {
            adapters.put(normalize(alias), adapter);
        }
    }

    private String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            return CloudProtocolAdapter.DEFAULT_PROVIDER;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
