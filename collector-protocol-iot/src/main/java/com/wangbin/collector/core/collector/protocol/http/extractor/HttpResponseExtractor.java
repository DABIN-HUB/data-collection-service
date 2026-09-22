package com.wangbin.collector.core.collector.protocol.http.extractor;

import com.wangbin.collector.common.domain.entity.DataPoint;
import java.util.List;
import java.util.Map;

/** HTTP 响应提取策略。 */
public interface HttpResponseExtractor {
    Map<String, Object> extract(byte[] response, List<DataPoint> points, Map<String, Object> config);
}
