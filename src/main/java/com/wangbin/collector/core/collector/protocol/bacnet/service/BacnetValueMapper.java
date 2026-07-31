package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValueKind;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.ProcessResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 定义当前模块的业务组件。
 */
public class BacnetValueMapper {

    /**
     * 解析或转换业务数据。
     */
    public ProcessResult map(DataQualityProcessor dataQualityProcessor,
                             DataPoint point,
                             BacnetAddress address,
                             Object rawValue,
                             String source,
                             String deviceId,
                             BiFunction<DataPoint, Object, Object> dataConverter) {
        BacnetReadValue readValue = normalize(point, address, rawValue, dataConverter);
        ProcessContext context = new ProcessContext();
        context.addAttribute("deviceId", deviceId);
        ProcessResult processResult = readValue.complex()
                ? ProcessResult.success(readValue.rawValue(), readValue.processedValue(), "BACnet complex value passthrough")
                : dataQualityProcessor.process(context, point, readValue.processedValue());
        enrich(processResult, address, source, readValue);
        return processResult;
    }

    /**
     * 解析或转换业务数据。
     */
    public BacnetReadValue normalize(DataPoint point,
                                     BacnetAddress address,
                                     Object rawValue,
                                     BiFunction<DataPoint, Object, Object> dataConverter) {
        if (rawValue == null) {
            return new BacnetReadValue(null, null, null, "null_value", false, Collections.emptyMap());
        }
        if (rawValue instanceof BacnetReadPropertyResponse response) {
            rawValue = BacnetValue.builder()
                    .value(response.getValue())
                    .valueType(response.getValueType())
                    .kind(inferValueKind(response.getValue()))
                    .metadata(response.getValueMetadata() != null ? response.getValueMetadata() : Collections.emptyMap())
                    .build();
        }
        if (rawValue instanceof BacnetValue bacnetValue) {
            Object value = bacnetValue.getValue();
            boolean complex = bacnetValue.isComplex()
                    || value instanceof Map<?, ?>
                    || value instanceof List<?>
                    || value instanceof Object[];
            if (complex) {
                return new BacnetReadValue(rawValue,
                        value,
                        bacnetValue.getValueType(),
                        "bacnet_complex_passthrough",
                        true,
                        bacnetValue.getMetadata());
            }
            if (isStringLike(address, value) || value instanceof boolean[] || value instanceof String) {
                return new BacnetReadValue(rawValue,
                        value,
                        bacnetValue.getValueType(),
                        "protocol_string_passthrough",
                        false,
                        bacnetValue.getMetadata());
            }
            return new BacnetReadValue(rawValue,
                    dataConverter.apply(point, value),
                    bacnetValue.getValueType(),
                    "default_scalar_conversion",
                    false,
                    bacnetValue.getMetadata());
        }
        if (isStringLike(address, rawValue) || rawValue instanceof boolean[] || rawValue instanceof String) {
            return new BacnetReadValue(rawValue, rawValue, null, "protocol_string_passthrough", false, Collections.emptyMap());
        }
        return new BacnetReadValue(rawValue,
                dataConverter.apply(point, rawValue),
                null,
                "default_scalar_conversion",
                false,
                Collections.emptyMap());
    }

    /**
     * 执行当前业务逻辑。
     */
    private void enrich(ProcessResult processResult,
                        BacnetAddress address,
                        String source,
                        BacnetReadValue readValue) {
        processResult.addMetadata("address", address.getCanonicalAddress());
        processResult.addMetadata("objectType", address.getObjectType());
        processResult.addMetadata("instanceNumber", address.getInstanceNumber());
        processResult.addMetadata("propertyIdentifier", address.getPropertyIdentifier());
        processResult.addMetadata("source", source);
        processResult.addMetadata("processingMode", readValue.processingMode());
        if (readValue.valueType() != null) {
            processResult.addMetadata("bacnetValueType", readValue.valueType());
        }
        processResult.addMetadata("bacnetComplexValue", readValue.complex());
        if (readValue.bacnetMetadata() != null && !readValue.bacnetMetadata().isEmpty()) {
            processResult.addMetadata("bacnetValueMetadata", readValue.bacnetMetadata());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private BacnetValueKind inferValueKind(Object value) {
        if (value instanceof Map<?, ?>) {
            return BacnetValueKind.OBJECT;
        }
        if (value instanceof List<?> || value instanceof Object[]) {
            return BacnetValueKind.ARRAY;
        }
        return BacnetValueKind.PRIMITIVE;
    }

    private boolean isStringLike(BacnetAddress address, Object rawValue) {
        if (rawValue instanceof String) {
            return true;
        }
        String driverType = address.getDriverDataType();
        return driverType != null && ("STRING".equalsIgnoreCase(driverType)
                || "CHARACTER_STRING".equalsIgnoreCase(driverType));
    }

    /**
     * 执行当前业务逻辑。
     */
    public BacnetValueKind inferKind(Object value) {
        return inferValueKind(value);
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record BacnetReadValue(Object rawValue,
                                  Object processedValue,
                                  String valueType,
                                  String processingMode,
                                  boolean complex,
                                  Map<String, Object> bacnetMetadata) {
    }
}
