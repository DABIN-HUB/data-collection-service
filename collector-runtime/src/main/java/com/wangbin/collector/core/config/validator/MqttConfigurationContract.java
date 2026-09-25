package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared MQTT connection, topic and template contract for validation and transport. */
public final class MqttConfigurationContract {
    private static final Set<String> SCHEMES = Set.of("tcp", "ssl", "ws", "wss");
    private static final Set<String> TOPIC_KEYS = Set.of("deviceId", "device_id", "pointId", "pointCode", "address");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$?\\{([A-Za-z_][A-Za-z_0-9]*)\\}");

    private MqttConfigurationContract() { }

    public static String version(Object value) {
        String text = value == null ? "v5" : value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return "v5";
        if ("v3".equals(text) || "3".equals(text) || "3.1.1".equals(text) || "mqtt3".equals(text)) return "v3";
        if ("v5".equals(text) || "5".equals(text)) return "v5";
        throw new IllegalArgumentException("MQTT version must be v3 or v5");
    }

    public static int qos(Object value, int fallback, String field) {
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            int result = Integer.parseInt(value.toString().trim());
            if (result >= 0 && result <= 2) return result;
        } catch (NumberFormatException ignored) {
            // A supplied invalid QoS must never silently become the default.
        }
        throw new IllegalArgumentException("MQTT " + field + " must be 0, 1 or 2");
    }

    public static boolean flag(Object value, boolean fallback, String field) {
        if (value == null || value.toString().isBlank()) return fallback;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(value.toString())) return true;
        if ("false".equalsIgnoreCase(value.toString())) return false;
        throw new IllegalArgumentException("MQTT " + field + " must be boolean");
    }

    public static long number(Object value, long fallback, long min, long max, String field) {
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            long result = Long.parseLong(value.toString().trim());
            if (result >= min && result <= max) return result;
        } catch (NumberFormatException ignored) {
            // Reject supplied invalid numbers rather than silently clamping them.
        }
        throw new IllegalArgumentException("MQTT " + field + " must be between " + min + " and " + max);
    }

    public static String brokerUri(DeviceConnection config) {
        String url = config.getUrl();
        String legacy = config.getString("brokerUrl", null);
        if (hasText(url) && hasText(legacy) && !url.trim().equals(legacy.trim())) {
            throw new IllegalArgumentException("MQTT url conflicts with brokerUrl");
        }
        String uriText = hasText(url) ? url.trim() : hasText(legacy) ? legacy.trim() : null;
        if (uriText == null) {
            if (!hasText(config.getHost()) || config.getPort() == null || config.getPort() < 1
                    || config.getPort() > 65535) {
                throw new IllegalArgumentException("MQTT requires url/brokerUrl or valid host and port");
            }
            uriText = (Boolean.TRUE.equals(config.getSslEnabled()) ? "ssl" : "tcp")
                    + "://" + config.getHost() + ":" + config.getPort();
        }
        URI parsed = URI.create(uriText);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme) || parsed.getHost() == null || parsed.getPort() < 1
                || parsed.getPort() > 65535 || parsed.getUserInfo() != null || parsed.getQuery() != null
                || parsed.getFragment() != null
                || (("tcp".equals(scheme) || "ssl".equals(scheme))
                && parsed.getPath() != null && !parsed.getPath().isEmpty())) {
            throw new IllegalArgumentException("MQTT broker URL must be tcp/ssl/ws/wss://host:port");
        }
        boolean secure = "ssl".equals(scheme) || "wss".equals(scheme);
        if (secure != Boolean.TRUE.equals(config.getSslEnabled())) {
            throw new IllegalArgumentException("MQTT broker scheme and sslEnabled disagree");
        }
        return uriText;
    }

    public static String resolveTemplate(String template, Map<String, ?> values, Set<String> allowed) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!allowed.contains(key)) throw new IllegalArgumentException("MQTT unknown placeholder: " + key);
            Object value = values == null ? null : values.get(key);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("MQTT missing placeholder value: " + key);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(result);
        if (result.indexOf("${") >= 0
                || count(result, '{') != count(result, '}')) {
            throw new IllegalArgumentException("MQTT malformed template");
        }
        return result.toString();
    }

    private static int count(CharSequence value, char target) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == target) count++;
        }
        return count;
    }

    public static String resolveTopic(String template, Map<String, ?> values) {
        return resolveTemplate(template, values, TOPIC_KEYS);
    }

    public static void filter(String filter) {
        validTopic(filter, "subscribe filter");
        String[] levels = filter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            if (levels[i].contains("#") && (!"#".equals(levels[i]) || i != levels.length - 1)) {
                throw new IllegalArgumentException("MQTT # must occupy the final level");
            }
            if (levels[i].contains("+") && !"+".equals(levels[i])) {
                throw new IllegalArgumentException("MQTT + must occupy an entire level");
            }
        }
    }

    public static void publishTopic(String topic) {
        validTopic(topic, "publish topic");
        if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            throw new IllegalArgumentException("MQTT publish topic cannot contain wildcards");
        }
    }

    public static boolean matches(String filter, String actual) {
        filter(filter);
        publishTopic(actual);
        if (actual.startsWith("$") && !filter.startsWith("$")) return false;
        String[] patterns = filter.split("/", -1);
        String[] levels = actual.split("/", -1);
        for (int i = 0; i < patterns.length; i++) {
            if ("#".equals(patterns[i])) return true;
            if (i == levels.length) return false;
            if (!"+".equals(patterns[i]) && !patterns[i].equals(levels[i])) return false;
        }
        return patterns.length == levels.length;
    }

    public static void validate(DeviceConnection config, String deviceId) {
        validate(config, deviceId, true);
    }

    /**
     * Validates all non-transport fields while allowing provider construction fixtures to defer endpoint validation.
     */
    public static void validate(DeviceConnection config, String deviceId, boolean requireEndpoint) {
        if (requireEndpoint || hasBrokerEndpoint(config)) {
            brokerUri(config);
        }
        version(config.getProperty("version"));
        for (String field : new String[]{"subscribeQos", "publishQos", "willQos"}) {
            qos(config.getProperty(field), 1, field);
        }
        for (String field : new String[]{"insecureSkipVerify", "willRetained", "retained"}) {
            flag(config.getProperty(field), false, field);
        }
        flag(config.getProperty("cleanSession"), true, "cleanSession");
        flag(config.getProperty("autoReconnect"), true, "autoReconnect");
        if (flag(config.getProperty("insecureSkipVerify"), false, "insecureSkipVerify")
                && !Boolean.TRUE.equals(config.getSslEnabled())) {
            throw new IllegalArgumentException("MQTT insecureSkipVerify requires SSL");
        }
        if (!flag(config.getProperty("cleanSession"), true, "cleanSession")
                && !hasText(config.getClientId()) && !hasText(config.getString("clientId", null))) {
            throw new IllegalArgumentException("MQTT persistent session requires a stable clientId");
        }
        number(config.getConnectTimeout(), 5000, 1, Integer.MAX_VALUE, "connectTimeout");
        number(config.getReadTimeout(), 30000, 1, Integer.MAX_VALUE, "readTimeout");
        number(config.getHeartbeatInterval(), 30000, 1000, Integer.MAX_VALUE, "heartbeatInterval");
        number(config.getProperty("sessionExpiryInterval"), 86400, 0, 4294967295L, "sessionExpiryInterval");
        number(config.getProperty("receiveMaximum"), 65535, 1, 65535, "receiveMaximum");
        number(config.getMaxPendingMessages(), 5000, 1, Integer.MAX_VALUE, "maxPendingMessages");
        number(config.getDispatchBatchSize(), 1, 1, Integer.MAX_VALUE, "dispatchBatchSize");
        number(config.getDispatchFlushInterval(), 0, 0, Long.MAX_VALUE, "dispatchFlushInterval");
        String overflow = config.getOverflowStrategy();
        if (overflow != null && !Set.of("BLOCK", "DROP_LATEST", "DROP_OLDEST")
                .contains(overflow.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("MQTT invalid overflowStrategy");
        }
        Map<String, ?> values = Map.of("deviceId", deviceId == null ? "" : deviceId,
                "device_id", deviceId == null ? "" : deviceId);
        Object topics = config.getProperty("subscribeTopics");
        if (topics instanceof Collection<?> collection) {
            for (Object topic : collection) filter(resolveTopic(String.valueOf(topic), values));
        } else if (topics != null) {
            for (String topic : topics.toString().split(",", -1)) {
                if (!topic.isBlank()) filter(resolveTopic(topic.trim(), values));
            }
        }
        for (String field : new String[]{"publishTopic", "willTopic", "authTopic"}) {
            String topic = config.getString(field, null);
            if (hasText(topic)) publishTopic(resolveTopic(topic, values));
        }
    }

    private static void validTopic(String topic, String type) {
        if (topic == null || topic.isEmpty() || topic.indexOf('\0') >= 0
                || topic.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("MQTT invalid " + type);
        }
    }

    public static boolean hasBrokerEndpoint(DeviceConnection config) {
        return hasText(config.getUrl()) || hasText(config.getString("brokerUrl", null))
                || (hasText(config.getHost()) && config.getPort() != null && config.getPort() > 0);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
