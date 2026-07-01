package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class BacnetWriteRequestBuilder {

    public BacnetWritePropertyRequest buildSingle(DataPoint point,
                                                  BacnetAddress address,
                                                  Object value,
                                                  String valueType,
                                                  Integer priority,
                                                  int invokeId,
                                                  int remoteDeviceInstance) {
        return BacnetWritePropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .value(value)
                .valueType(valueType)
                .priority(priority)
                .invokeId(invokeId)
                .remoteDeviceInstance(remoteDeviceInstance)
                .build();
    }

    public BacnetWritePropertyMultipleRequest buildMultiple(Map<DataPoint, Object> points,
                                                            Function<DataPoint, BacnetAddress> addressResolver,
                                                            BiFunction<DataPoint, BacnetAddress, String> valueTypeResolver,
                                                            Function<DataPoint, Integer> priorityResolver,
                                                            Supplier<Integer> invokeIdSupplier,
                                                            int remoteDeviceInstance) {
        Map<String, BacnetWritePropertyMultipleRequest.WriteAccessSpec.WriteAccessSpecBuilder> grouped = new LinkedHashMap<>();
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            BacnetAddress address = addressResolver.apply(point);
            String groupKey = address.getObjectTypeId() + ":" + address.getInstanceNumber();
            BacnetWritePropertyMultipleRequest.WriteAccessSpec.WriteAccessSpecBuilder builder = grouped.computeIfAbsent(
                    groupKey,
                    ignored -> BacnetWritePropertyMultipleRequest.WriteAccessSpec.builder()
                            .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                            .objectInstance(address.getInstanceNumber()));
            builder.propertyValue(BacnetWritePropertyMultipleRequest.PropertyValueSpec.builder()
                    .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                    .arrayIndex(address.getArrayIndex())
                    .value(entry.getValue())
                    .valueType(valueTypeResolver.apply(point, address))
                    .priority(priorityResolver.apply(point))
                    .build());
        }
        BacnetWritePropertyMultipleRequest.BacnetWritePropertyMultipleRequestBuilder requestBuilder =
                BacnetWritePropertyMultipleRequest.builder()
                        .invokeId(invokeIdSupplier.get())
                        .remoteDeviceInstance(remoteDeviceInstance);
        for (BacnetWritePropertyMultipleRequest.WriteAccessSpec.WriteAccessSpecBuilder builder : grouped.values()) {
            requestBuilder.writeAccessSpecification(builder.build());
        }
        return requestBuilder.build();
    }
}
