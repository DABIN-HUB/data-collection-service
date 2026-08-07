package com.wangbin.collector.core.cloud.protocol.alink.topic;

import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Alink topic 解析器。
 */
@Component
public class AlinkTopicParser {

    /**
     * 解析或转换业务数据。
     */
    public Optional<AlinkTopic> parse(String rawTopic) {
        if (!StringUtils.hasText(rawTopic)) {
            return Optional.empty();
        }
        String normalized = rawTopic.replace('\\', '/').trim();
        boolean reply = normalized.endsWith(AlinkTopicBuilder.DEFAULT_REPLY_SUFFIX);
        String topic = reply
                ? normalized.substring(0, normalized.length() - AlinkTopicBuilder.DEFAULT_REPLY_SUFFIX.length())
                : normalized;
        String[] segments = topic.split("/");
        int sysIndex = findPrefixIndex(segments);
        if (sysIndex < 0 || segments.length <= sysIndex + 4) {
            return Optional.empty();
        }
        String productKey = segments[sysIndex + 1];
        String deviceName = segments[sysIndex + 2];
        StringBuilder methodPath = new StringBuilder();
        for (int i = sysIndex + 3; i < segments.length; i++) {
            if (segments[i] == null || segments[i].isBlank()) {
                continue;
            }
            if (!methodPath.isEmpty()) {
                methodPath.append('/');
            }
            methodPath.append(segments[i]);
        }
        return AlinkMethod.fromPath(methodPath.toString())
                .map(method -> new AlinkTopic(
                        rawTopic,
                        AlinkTopicBuilder.DEFAULT_PREFIX,
                        CloudDeviceIdentity.of(productKey, deviceName),
                        method,
                        reply));
    }

    /**
     * 查询并返回业务数据。
     */
    private int findPrefixIndex(String[] segments) {
        for (int i = 0; i < segments.length; i++) {
            if ("sys".equals(segments[i])) {
                return i;
            }
        }
        return -1;
    }
}
