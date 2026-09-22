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
        try {
            Object root = JSON.parse(new String(response, StandardCharsets.UTF_8));
            String path = String.valueOf(config.getOrDefault("responsePath", config.getOrDefault("jsonPath", "$")));
            Object value = JSONPath.eval(root, path);
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
        } catch (Exception exception) {
            return Collections.emptyMap();
        }
    }
}
