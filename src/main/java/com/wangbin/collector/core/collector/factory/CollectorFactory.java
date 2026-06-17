package com.wangbin.collector.core.collector.factory;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptor;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 采集器工厂：根据协议类型创建对应的采集器实现。
 */
@Slf4j
@Component
public class CollectorFactory {

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;

    private final Map<String, CollectorCreator> collectorCreators = new HashMap<>();

    public CollectorFactory(ProtocolDescriptorRegistry protocolDescriptorRegistry) {
        this.protocolDescriptorRegistry = protocolDescriptorRegistry;
        registerCollectorCreators();
    }

    /**
     * 创建采集器并完成初始化。
     */
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

    /**
     * 注册协议与采集器创建器映射。
     */
    public void registerCollector(String protocolType, CollectorCreator creator) {
        collectorCreators.put(normalize(protocolType), creator);
        log.info("Collector registered, protocolType={}", protocolType);
    }

    /**
     * 获取当前支持的所有协议类型。
     */
    public String[] getSupportedProtocols() {
        return collectorCreators.keySet().toArray(new String[0]);
    }

    /**
     * 判断是否支持某个协议。
     */
    public boolean supportsProtocol(String protocolType) {
        return collectorCreators.containsKey(normalize(protocolType));
    }

    /**
     * 注册内置采集器。
     */
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

    /**
     * 使用 Spring BeanFactory 创建实例，保证 AOP 生效。
     */
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
