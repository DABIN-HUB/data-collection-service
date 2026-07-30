package com.wangbin.collector.core.collector.factory;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptor;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Creates protocol collectors by protocol type.
 */
@Slf4j
@Component
public class CollectorFactory {

    private final AutowireCapableBeanFactory beanFactory;
    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;
    private final Map<String, CollectorCreator> collectorCreators = new HashMap<>();

    public CollectorFactory(AutowireCapableBeanFactory beanFactory,
                            ProtocolDescriptorRegistry protocolDescriptorRegistry) {
        this.beanFactory = beanFactory;
        this.protocolDescriptorRegistry = protocolDescriptorRegistry;
        registerCollectorCreators();
    }

    public ProtocolCollector createCollector(DeviceInfo deviceInfo) throws CollectorException {
        String protocolType = deviceInfo.getProtocolType();
        if (protocolType == null || protocolType.isEmpty()) {
            throw new IllegalArgumentException("Protocol type cannot be empty");
        }

        CollectorCreator creator = collectorCreators.get(normalize(protocolType));
        if (creator == null) {
            throw new CollectorException(
                    String.format("Unsupported protocol type: %s", protocolType),
                    deviceInfo.getDeviceId(),
                    null
            );
        }

        try {
            ProtocolCollector collector = creator.create(deviceInfo);
            collector.init(deviceInfo);
            log.info("Collector created successfully, deviceId={}, protocolType={}",
                    deviceInfo.getDeviceId(), protocolType);
            return collector;
        } catch (Exception e) {
            log.error("Collector creation failed, deviceId={}, protocolType={}",
                    deviceInfo.getDeviceId(), protocolType, e);
            throw new CollectorException("Collector creation failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    public void registerCollector(String protocolType, CollectorCreator creator) {
        collectorCreators.put(normalize(protocolType), creator);
        log.info("Collector registered, protocolType={}", protocolType);
    }

    public String[] getSupportedProtocols() {
        return collectorCreators.keySet().toArray(new String[0]);
    }

    public boolean supportsProtocol(String protocolType) {
        return collectorCreators.containsKey(normalize(protocolType));
    }

    private void registerCollectorCreators() {
        collectorCreators.clear();
        for (ProtocolDescriptor descriptor : protocolDescriptorRegistry.primaryDescriptors()) {
            registerCollector(descriptor.code(), descriptor.collectorClass());
            for (String alias : descriptor.aliases()) {
                registerCollector(alias, descriptor.collectorClass());
            }
        }

        log.info("CollectorFactory initialized, supported protocol count={}", collectorCreators.size());
    }

    private String normalize(String protocolType) {
        return protocolType == null ? "" : protocolType.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    public void registerCollector(String protocolType, Class<? extends ProtocolCollector> collectorClass) {
        registerCollector(protocolType, deviceInfo -> instantiateCollector(protocolType, collectorClass));
    }

    private ProtocolCollector instantiateCollector(String protocolType,
                                                   Class<? extends ProtocolCollector> collectorClass) {
        try {
            if (beanFactory != null) {
                return beanFactory.createBean(collectorClass);
            }
            return collectorClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Failed to instantiate collector, protocolType=%s, class=%s",
                            protocolType, collectorClass.getName()),
                    e
            );
        }
    }

    @FunctionalInterface
    public interface CollectorCreator {
        ProtocolCollector create(DeviceInfo deviceInfo) throws Exception;
    }
}