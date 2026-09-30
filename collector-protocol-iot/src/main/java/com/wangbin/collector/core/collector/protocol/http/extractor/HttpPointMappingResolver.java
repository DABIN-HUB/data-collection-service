package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Response-side mapping identity: explicit responseKey, address, code, then id. */
public final class HttpPointMappingResolver {
    private HttpPointMappingResolver() { }

    public static List<String> keys(DataPoint point) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Object explicit = point.getAdditionalConfig() == null ? null : point.getAdditionalConfig().get("responseKey");
        add(keys, explicit == null ? null : explicit.toString());
        String address = point.getAddress();
        // A historical slash-delimited address is response-side identity, not a request endpoint.
        if (address != null && !address.isBlank()) {
            add(keys, address);
            if (address.contains("/")) add(keys, address.substring(address.lastIndexOf('/') + 1));
        }
        add(keys, point.getPointCode());
        add(keys, point.getPointId());
        return new ArrayList<>(keys);
    }

    public static Object lookup(Map<String, ?> values, DataPoint point) {
        for (String key : keys(point)) {
            if (values.containsKey(key) && values.get(key) != null) return values.get(key);
        }
        return null;
    }

    private static void add(LinkedHashSet<String> keys, String value) {
        if (value != null && !value.isBlank()) keys.add(value.trim());
    }
}
