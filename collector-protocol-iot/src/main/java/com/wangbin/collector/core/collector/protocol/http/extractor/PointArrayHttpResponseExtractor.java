package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 从 JSON 数组中按点位编码匹配值。 */
public class PointArrayHttpResponseExtractor implements HttpResponseExtractor {
    @Override
    public Map<String, Object> extract(byte[] response, List<DataPoint> points, Map<String, Object> config) {
        Object root = JSON.parse(new String(response, StandardCharsets.UTF_8));
        Object arrayValue = JSONPath.eval(root, String.valueOf(config.getOrDefault("responseArrayPath", "$.points")));
        if (!(arrayValue instanceof JSONArray array)) return Map.of();
        String keyField = String.valueOf(config.getOrDefault("responseKeyField", "name"));
        String valueField = String.valueOf(config.getOrDefault("responseValueField", "value"));
        Map<String, Object> byKey = new HashMap<>();
        for (Object item : array) {
            if (item instanceof JSONObject object && object.get(keyField) != null) {
                byKey.put(object.getString(keyField), object.get(valueField));
            }
        }
        Map<String, Object> result = new HashMap<>();
        for (DataPoint point : points) {
            Object value = byKey.get(point.getPointCode());
            if (value == null) value = byKey.get(point.getPointId());
            if (value != null) result.put(point.getPointId(), value);
        }
        return result;
    }
}
