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
 * 按协议类型创建采集器。
 */
@Slf4j
@Component
public class CollectorFactory {

    private final AutowireCapableBeanFactory beanFactory;
    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;
    private final Map<String, CollectorCreator> collectorCreators = new HashMap<>();

    /**
     * 创建当前组件实例。
     */
    public CollectorFactory(AutowireCapableBeanFactory beanFactory,
                            ProtocolDescriptorRegistry protocolDescriptorRegistry) {
        this.beanFactory = beanFactory;
        this.protocolDescriptorRegistry = protocolDescriptorRegistry;
        registerCollectorCreators();
    }

    /**
     * 创建并返回业务对象。
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
            log.info("采集器 创建成功, 设备={}, 协议类型={}",
                    deviceInfo.getDeviceId(), protocolType);
            return collector;
        } catch (Exception e) {
            log.error("采集器 创建失败, 设备={}, 协议类型={}",
                    deviceInfo.getDeviceId(), protocolType, e);
            throw new CollectorException("Collector creation failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    public void registerCollector(String protocolType, CollectorCreator creator) {
        collectorCreators.put(normalize(protocolType), creator);
        log.info("采集器 已注册, 协议类型={}", protocolType);
    }

    public String[] getSupportedProtocols() {
        return collectorCreators.keySet().toArray(new String[0]);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean supportsProtocol(String protocolType) {
        return collectorCreators.containsKey(normalize(protocolType));
    }

    /**
     * 维护注册或订阅关系。
     */
    private void registerCollectorCreators() {
        collectorCreators.clear();
        for (ProtocolDescriptor descriptor : protocolDescriptorRegistry.primaryDescriptors()) {
            registerCollector(descriptor.code(), descriptor.collectorClass());
            for (String alias : descriptor.aliases()) {
                registerCollector(alias, descriptor.collectorClass());
            }
        }

        log.info("CollectorFactory 已初始化, 支持协议数量={}", collectorCreators.size());
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalize(String protocolType) {
        return protocolType == null ? "" : protocolType.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    /**
     * 维护注册或订阅关系。
     */
    public void registerCollector(String protocolType, Class<? extends ProtocolCollector> collectorClass) {
        registerCollector(protocolType, deviceInfo -> instantiateCollector(protocolType, collectorClass));
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    public interface CollectorCreator {
        /**
         * 创建并返回业务对象。
         */
        ProtocolCollector create(DeviceInfo deviceInfo) throws Exception;
    }
}