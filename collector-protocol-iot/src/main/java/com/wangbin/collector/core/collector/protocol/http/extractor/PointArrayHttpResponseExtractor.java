package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 从 JSON 数组中按点位编码匹配值。 */
@Slf4j
public class PointArrayHttpResponseExtractor implements HttpResponseExtractor {
    @Override
    public Map<String, Object> extract(byte[] response, List<DataPoint> points, Map<String, Object> config) {
        if (response == null || response.length == 0 || points == null || points.isEmpty()) {
            return Collections.emptyMap();
        }
        Object root;
        try {
            root = JSON.parse(new String(response, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("HTTP response parse failed", exception);
        }

        Map<String, Object> safeConfig = config != null ? config : Collections.emptyMap();
        String arrayPath = String.valueOf(safeConfig.getOrDefault("responseArrayPath", "$.points"));
        Object arrayValue;
        try {
            arrayValue = JSONPath.eval(root, arrayPath);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("HTTP JSONPath extraction failed: " + arrayPath, exception);
        }
        if (arrayValue == null) return Collections.emptyMap();
        if (!(arrayValue instanceof JSONArray array)) throw new IllegalArgumentException("HTTP responseArrayPath is not an array");

        String keyField = String.valueOf(safeConfig.getOrDefault("responseKeyField", "name"));
        String valueField = String.valueOf(safeConfig.getOrDefault("responseValueField", "value"));
        Map<String, Object> byKey = new HashMap<>();
        for (Object item : array) {
            if (!(item instanceof JSONObject object) || object.get(keyField) == null) {
                log.warn("HTTP POINT_NOT_FOUND: point array item has no key field {}", keyField);
                continue;
            }
            if (!object.containsKey(valueField)) {
                log.warn("HTTP POINT_NOT_FOUND: point array item has no value field {}", valueField);
                continue;
            }
            String key = object.getString(keyField);
            if (byKey.containsKey(key)) throw new IllegalArgumentException("HTTP point array has duplicate key: " + key);
            byKey.put(key, object.get(valueField));
        }
        Map<String, Object> result = new HashMap<>();
        for (DataPoint point : points) {
            Object value = HttpPointMappingResolver.lookup(byKey, point);
            if (value != null) result.put(point.getPointId(), value);
        }
        return result;
    }
}
