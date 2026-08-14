package com.wangbin.collector.core.collector.protocol.custom.codec;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于受控占位符的自定义协议请求编码器。
 */
public final class CustomRequestEncoder {

    /**
     * 创建当前组件实例。
     */
    private CustomRequestEncoder() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeRead(DataPoint point, DeviceConnection connection) {
        String template = point.getAdditionalConfig("requestTemplate",
                connection.getString("readRequestTemplate", null));
        return encodeTemplate(template, resolveEncoding(point, connection, "requestEncoding"),
                variables(point, null), resolveCharset(point, connection));
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeWrite(DataPoint point, Object value, DeviceConnection connection) {
        String template = point.getAdditionalConfig("writeRequestTemplate",
                connection.getString("writeRequestTemplate", null));
        return encodeTemplate(template, resolveEncoding(point, connection, "writeRequestEncoding"),
                variables(point, value), resolveCharset(point, connection));
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeTemplate(String template,
                                         String encoding,
                                         Map<String, String> variables,
                                         Charset charset) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("自定义协议请求模板不能为空");
        }
        String resolved = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        if (resolved.contains("${")) {
            throw new IllegalArgumentException("请求模板包含未解析的占位符: " + resolved);
        }
        return switch (encoding.toUpperCase()) {
            case "HEX" -> CustomFrameCodec.decodeHex(resolved);
            case "BASE64" -> Base64.getDecoder().decode(resolved.trim());
            case "TEXT" -> resolved.getBytes(charset);
            default -> throw new IllegalArgumentException("不支持的请求编码: " + encoding);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private static Map<String, String> variables(DataPoint point, Object value) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(CommonMapKeys.POINT_ID, safe(point.getPointId()));
        variables.put(CommonMapKeys.POINT_CODE, safe(point.getPointCode()));
        variables.put(CommonMapKeys.ADDRESS, safe(point.getAdditionalConfig("requestAddress", point.getAddress())));
        variables.put("addressHex", resolveAddressHex(point));
        variables.put(CommonMapKeys.VALUE, value == null ? "" : String.valueOf(value));
        variables.put("valueHex", value == null ? "" : CustomFrameCodec.encodeHex(CustomValueCodec.encode(value, point)));
        return variables;
    }

    /**
     * 解析或转换业务数据。
     */
    private static String resolveAddressHex(DataPoint point) {
        String configured = point.getAdditionalConfig("addressHex", null);
        if (configured != null && !configured.isBlank()) {
            return configured.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        }
        String requestAddress = point.getAdditionalConfig("requestAddress", null);
        if (requestAddress == null || requestAddress.isBlank()) {
            return "";
        }
        int width = Math.max(2, point.getAdditionalConfig("addressHexWidth", 4));
        long numericAddress = Long.decode(requestAddress.trim());
        return String.format("%0" + width + "X", numericAddress);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String resolveEncoding(DataPoint point, DeviceConnection connection, String key) {
        String fallbackKey = "writeRequestEncoding".equals(key) ? "requestEncoding" : key;
        String connectionValue = connection.getString(key, connection.getString(fallbackKey, "HEX"));
        return point.getAdditionalConfig(key, connectionValue);
    }

    /**
     * 解析或转换业务数据。
     */
    private static Charset resolveCharset(DataPoint point, DeviceConnection connection) {
        String connectionCharset = connection.getString("charset", StandardCharsets.UTF_8.name());
        return Charset.forName(point.getAdditionalConfig("charset", connectionCharset));
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
