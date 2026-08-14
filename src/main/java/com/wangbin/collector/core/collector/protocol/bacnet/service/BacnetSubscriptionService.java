package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * 处理当前模块的业务服务。
 */
public class BacnetSubscriptionService {

    /**
     * 维护注册或订阅关系。
     */
    public SubscriptionBinding subscribe(DataPoint point,
                                         BacnetAddress address,
                                         boolean propertyLevel,
                                         boolean issueConfirmedNotifications,
                                         Integer lifetimeSeconds,
                                         Double covIncrement,
                                         IntSupplier processIdSupplier,
                                         IntSupplier invokeIdSupplier,
                                         int remoteDeviceInstance,
                                         ThrowingConsumer<BacnetSubscribeCovRequest> covExecutor,
                                         ThrowingConsumer<BacnetSubscribeCovPropertyRequest> covPropertyExecutor) throws Exception {
        int processIdentifier = processIdSupplier.getAsInt();
        if (propertyLevel) {
            BacnetSubscribeCovPropertyRequest request = BacnetSubscribeCovPropertyRequest.builder()
                    .subscriberProcessIdentifier(processIdentifier)
                    .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                    .objectInstance(address.getInstanceNumber())
                    .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                    .arrayIndex(address.getArrayIndex())
                    .issueConfirmedNotifications(issueConfirmedNotifications)
                    .lifetimeSeconds(lifetimeSeconds)
                    .covIncrement(covIncrement)
                    .invokeId(invokeIdSupplier.getAsInt())
                    .remoteDeviceInstance(remoteDeviceInstance)
                    .build();
            covPropertyExecutor.accept(request);
        } else {
            BacnetSubscribeCovRequest request = BacnetSubscribeCovRequest.builder()
                    .subscriberProcessIdentifier(processIdentifier)
                    .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                    .objectInstance(address.getInstanceNumber())
                    .issueConfirmedNotifications(issueConfirmedNotifications)
                    .lifetimeSeconds(lifetimeSeconds)
                    .invokeId(invokeIdSupplier.getAsInt())
                    .remoteDeviceInstance(remoteDeviceInstance)
                    .build();
            covExecutor.accept(request);
        }
        return new SubscriptionBinding(processIdentifier, address, propertyLevel, issueConfirmedNotifications);
    }

    /**
     * 维护注册或订阅关系。
     */
    public void unsubscribe(SubscriptionBinding binding,
                            IntSupplier invokeIdSupplier,
                            int remoteDeviceInstance,
                            ThrowingConsumer<BacnetSubscribeCovRequest> covExecutor,
                            ThrowingConsumer<BacnetSubscribeCovPropertyRequest> covPropertyExecutor) throws Exception {
        if (binding.propertyLevel()) {
            BacnetSubscribeCovPropertyRequest request = BacnetSubscribeCovPropertyRequest.builder()
                    .subscriberProcessIdentifier(binding.processIdentifier())
                    .objectType(BacnetObjectType.fromId(binding.address().getObjectTypeId()))
                    .objectInstance(binding.address().getInstanceNumber())
                    .propertyIdentifier(BacnetPropertyIdentifier.fromId(binding.address().getPropertyIdentifierId()))
                    .arrayIndex(binding.address().getArrayIndex())
                    .issueConfirmedNotifications(binding.issueConfirmedNotifications())
                    .lifetimeSeconds(0)
                    .invokeId(invokeIdSupplier.getAsInt())
                    .remoteDeviceInstance(remoteDeviceInstance)
                    .build();
            covPropertyExecutor.accept(request);
            return;
        }
        BacnetSubscribeCovRequest request = BacnetSubscribeCovRequest.builder()
                .subscriberProcessIdentifier(binding.processIdentifier())
                .objectType(BacnetObjectType.fromId(binding.address().getObjectTypeId()))
                .objectInstance(binding.address().getInstanceNumber())
                .issueConfirmedNotifications(binding.issueConfirmedNotifications())
                .lifetimeSeconds(0)
                .invokeId(invokeIdSupplier.getAsInt())
                .remoteDeviceInstance(remoteDeviceInstance)
                .build();
        covExecutor.accept(request);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean usePropertySubscription(DataPoint point,
                                           BacnetAddress address,
                                           Boolean connectionEnabled) {
        Boolean pointEnabled = point.getAdditionalConfig("covPropertyEnabled", null);
        if (pointEnabled != null) {
            return pointEnabled;
        }
        if (connectionEnabled != null) {
            return connectionEnabled;
        }
        return address.getArrayIndex() != null || point.getAdditionalConfig("covIncrement", null) != null;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean matchesNotification(int objectTypeId,
                                       int instanceNumber,
                                       int propertyIdentifierId,
                                       Integer arrayIndex,
                                       BacnetAddress address) {
        return address != null
                && address.getObjectTypeId() == objectTypeId
                && address.getInstanceNumber() == instanceNumber
                && address.getPropertyIdentifierId() == propertyIdentifierId
                && Objects.equals(address.getArrayIndex(), arrayIndex);
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        /**
         * 执行当前业务逻辑。
         */
        void accept(T value) throws Exception;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record SubscriptionBinding(int processIdentifier,
                                      BacnetAddress address,
                                      boolean propertyLevel,
                                      boolean issueConfirmedNotifications) {
    }
}
