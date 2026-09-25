package com.wangbin.collector.core.collector.protocol.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** MQTT 3.1.1/5 topic-filter validation and matching. */
public final class MqttTopicFilterMatcher {

    private static final int MAX_UTF8_BYTES = 65_535;

    private MqttTopicFilterMatcher() {
    }

    public static String validateFilter(String filter) {
        validateText(filter, "MQTT topic filter");
        String[] levels = filter.split("/", -1);
        for (int index = 0; index < levels.length; index++) {
            String level = levels[index];
            if (level.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("MQTT topic filter contains NUL");
            }
            if (level.contains("+") && !"+".equals(level)) {
                throw new IllegalArgumentException("MQTT '+' must occupy one complete topic level");
            }
            if (level.contains("#") && !("#".equals(level) && index == levels.length - 1)) {
                throw new IllegalArgumentException("MQTT '#' must occupy the final complete topic level");
            }
        }
        return filter;
    }

    public static String validatePublishTopic(String topic) {
        validateText(topic, "MQTT publish topic");
        if (topic.indexOf('\u0000') >= 0 || topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            throw new IllegalArgumentException("MQTT publish topic must not contain wildcards or NUL");
        }
        return topic;
    }

    public static boolean matches(String filter, String topic) {
        validateFilter(filter);
        validatePublishTopic(topic);
        String[] filterLevels = filter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);
        if (!topicLevels[0].isEmpty() && topicLevels[0].charAt(0) == '$'
                && ("+".equals(filterLevels[0]) || "#".equals(filterLevels[0]))) {
            return false;
        }
        int topicIndex = 0;
        for (String filterLevel : filterLevels) {
            if ("#".equals(filterLevel)) {
                return true;
            }
            if (topicIndex >= topicLevels.length) {
                return false;
            }
            if (!"+".equals(filterLevel) && !filterLevel.equals(topicLevels[topicIndex])) {
                return false;
            }
            topicIndex++;
        }
        return topicIndex == topicLevels.length;
    }

    private static void validateText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(label + " exceeds MQTT UTF-8 length limit");
        }
    }
}
