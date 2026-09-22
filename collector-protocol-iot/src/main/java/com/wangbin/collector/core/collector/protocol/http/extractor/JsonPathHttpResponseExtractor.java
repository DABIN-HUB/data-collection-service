package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 使用 fastjson2 JSONPath 提取单点或点位映射。 */
public class JsonPathHttpResponseExtractor implements HttpResponseExtractor {
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
        String path = String.valueOf(safeConfig.getOrDefault(
                "responsePath", safeConfig.getOrDefault("jsonPath", "$")));
        Object value;
        try {
            value = JSONPath.eval(root, path);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("HTTP JSONPath extraction failed: " + path, exception);
        }
        if (value == null) {
            return Collections.emptyMap();
        }
        if (points.size() == 1) {
            return Map.of(points.get(0).getPointId(), value);
        }
        if (value instanceof JSONObject object) {
            Map<String, Object> result = new HashMap<>();
            for (DataPoint point : points) {
                Object item = object.get(point.getPointId());
                if (item == null && point.getPointCode() != null) item = object.get(point.getPointCode());
                if (item != null) result.put(point.getPointId(), item);
            }
            return result;
        }
        return Collections.emptyMap();
    }
}
