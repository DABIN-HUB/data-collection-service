package com.wangbin.collector.core.collector.protocol.plc4x.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class PointTypeResolverSupport {

    private PointTypeResolverSupport() {
    }

    public static <A, T extends PlcTypeDescriptor> T resolveOrNull(
            DataPoint point,
            A address,
            Function<A, String> addressTypeExtractor,
            Function<String, T> addressTypeResolver,
            Function<String, T> driverTypeResolver,
            Function<String, T> platformTypeResolver,
            boolean includePointDataTypeInDriverChain,
            String... driverTypeConfigKeys) {
        String addressTypeText = address != null && addressTypeExtractor != null
                ? addressTypeExtractor.apply(address)
                : null;
        if (hasText(addressTypeText) && addressTypeResolver != null) {
            T addressType = addressTypeResolver.apply(addressTypeText);
            if (addressType != null) {
                return addressType;
            }
        }

        String driverTypeText = resolveDriverTypeText(point, includePointDataTypeInDriverChain, driverTypeConfigKeys);
        if (hasText(driverTypeText) && driverTypeResolver != null) {
            T driverType = driverTypeResolver.apply(driverTypeText);
            if (driverType != null) {
                return driverType;
            }
        }

        String platformTypeText = point != null ? point.getDataType() : null;
        if (hasText(platformTypeText) && platformTypeResolver != null) {
            return platformTypeResolver.apply(platformTypeText);
        }
        return null;
    }

    private static String resolveDriverTypeText(DataPoint point, boolean includePointDataTypeInDriverChain,
                                                String... driverTypeConfigKeys) {
        List<String> candidates = new ArrayList<>();
        if (driverTypeConfigKeys != null) {
            for (String key : driverTypeConfigKeys) {
                candidates.add(additionalConfigAsString(point, key));
            }
        }
        if (includePointDataTypeInDriverChain) {
            candidates.add(point != null ? point.getDataType() : null);
        }
        return firstNonBlank(candidates.toArray(String[]::new));
    }

    private static String additionalConfigAsString(DataPoint point, String key) {
        if (point == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = point.getAdditionalConfig(key);
        return value != null ? value.toString() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}