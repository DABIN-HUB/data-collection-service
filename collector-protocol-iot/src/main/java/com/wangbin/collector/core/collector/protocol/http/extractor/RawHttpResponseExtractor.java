package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HTTP 原始响应提取器，兼容平台已有响应格式。 */
public class RawHttpResponseExtractor implements HttpResponseExtractor {
    @Override
    public Map<String, Object> extract(byte[] response, List<DataPoint> points, Map<String, Object> config) {
        Map<String, Object> result = new HashMap<>();
        if (response == null || response.length == 0) return result;
        String text = new String(response, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return result;
        Object parsed;
        try { parsed = JSON.parse(text); } catch (Exception exception) {
            if (text.startsWith("{") || text.startsWith("[") || text.startsWith("\"")) {
                throw new IllegalArgumentException("HTTP INVALID_JSON: malformed structured response", exception);
            }
            if (points.size() == 1) result.put(points.get(0).getPointId(), text);
            return result;
        }
        if (parsed instanceof JSONObject object) {
            Object values = object.get("values");
            if (values instanceof JSONObject valuesObject) {
                putMap(points, result, valuesObject);
            } else if (object.get("pointId") != null && object.containsKey("value")) {
                putIdentified(points, result, object.getString("pointId"), object.get("value"));
            } else {
                putMap(points, result, object);
            }
        } else if (parsed instanceof JSONArray array) {
            for (Object item : array) {
                if (item instanceof JSONObject object && object.get("pointId") != null && object.containsKey("value")) {
                    putIdentified(points, result, object.getString("pointId"), object.get("value"));
                }
            }
        } else if (points.size() == 1) {
            result.put(points.get(0).getPointId(), parsed);
        }
        return result;
    }

    private void putMap(List<DataPoint> points, Map<String, Object> result, JSONObject source) {
        for (DataPoint point : points) {
            Object value = HttpPointMappingResolver.lookup(source, point);
            if (value != null) result.put(point.getPointId(), value);
        }
    }

    private void putIdentified(List<DataPoint> points, Map<String, Object> result, String key, Object value) {
        if (value == null) return;
        for (DataPoint point : points) {
            if (HttpPointMappingResolver.keys(point).contains(key)) result.put(point.getPointId(), value);
        }
    }
}
