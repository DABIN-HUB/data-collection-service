package com.wangbin.collector.core.report.downlink;

import com.wangbin.collector.common.constant.MessageConstant;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT 下行业务处理结果，用于生成平台调用响应。
 */
@Getter
public class MqttDownlinkResult {

    private final String messageId;
    private final String method;
    private final String deviceId;
    private final int code;
    private final String message;
    private final Map<String, Object> data;
    private final boolean responseRequired;

    /**
     * 创建当前组件实例。
     */
    private MqttDownlinkResult(String messageId,
                               String method,
                               String deviceId,
                               int code,
                               String message,
                               Map<String, Object> data,
                               boolean responseRequired) {
        this.messageId = messageId;
        this.method = method;
        this.deviceId = deviceId;
        this.code = code;
        this.message = message;
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        this.responseRequired = responseRequired;
    }

    /**
     * 构造标准业务结果。
     */
    public static MqttDownlinkResult success(String messageId,
                                             String method,
                                             String deviceId,
                                             Map<String, Object> data) {
        return of(messageId, method, deviceId, 0, "success", data);
    }

    /**
     * 创建并返回业务对象。
     */
    public static MqttDownlinkResult of(String messageId,
                                        String method,
                                        String deviceId,
                                        int code,
                                        String message,
                                        Map<String, Object> data) {
        return new MqttDownlinkResult(messageId, method, deviceId, code, message, data, true);
    }

    /**
     * 构造标准业务结果。
     */
    public static MqttDownlinkResult ignored(String method) {
        return new MqttDownlinkResult(null, method, null, 0, "ignored", Map.of(), false);
    }

    /**
     * 解析或转换业务数据。
     */
    public Map<String, Object> toResponseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (messageId != null && !messageId.isBlank()) {
            body.put("id", messageId);
            body.put(MessageConstant.FIELD_MESSAGE_ID, messageId);
        }
        body.put("version", MessageConstant.MESSAGE_VERSION_1_0);
        if (method != null && !method.isBlank()) {
            body.put("method", method);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            body.put("deviceId", deviceId);
        }
        body.put("code", code);
        body.put("msg", message);
        body.put("timestamp", System.currentTimeMillis());
        body.put("data", data);
        return body;
    }
}
