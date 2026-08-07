package com.wangbin.collector.core.cloud.protocol.alink.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alink 设备生命周期消息编码器。
 */
@Component
public class AlinkLifecycleCodec {

    public static final int STATE_ONLINE = 1;
    public static final int STATE_OFFLINE = 2;

    private final ObjectMapper objectMapper;
    private final AlinkTopicBuilder topicBuilder;

    /**
     * 创建当前组件实例。
     */
    public AlinkLifecycleCodec(ObjectMapper objectMapper, AlinkTopicBuilder topicBuilder) {
        this.objectMapper = objectMapper;
        this.topicBuilder = topicBuilder;
    }

    /**
     * 解析或转换业务数据。
     */
    public LifecycleMessage encodeGatewayOnline(CloudDeviceIdentity gatewayIdentity) {
        return encodeGatewayState(gatewayIdentity, STATE_ONLINE);
    }

    /**
     * 解析或转换业务数据。
     */
    public LifecycleMessage encodeGatewayOffline(CloudDeviceIdentity gatewayIdentity) {
        return encodeGatewayState(gatewayIdentity, STATE_OFFLINE);
    }

    /**
     * 解析或转换业务数据。
     */
    public LifecycleMessage encodeGatewayState(CloudDeviceIdentity gatewayIdentity, int state) {
        validateIdentity(gatewayIdentity, "网关身份无效，无法构建设备生命周期消息");
        return new LifecycleMessage(
                topicBuilder.build(gatewayIdentity, AlinkMethod.STATE_UPDATE, false),
                encodeBody(Map.of("state", state)));
    }

    /**
     * 解析或转换业务数据。
     */
    public LifecycleMessage encodeSubDeviceState(CloudDeviceIdentity gatewayIdentity,
                                                 List<CloudDeviceIdentity> subDeviceIdentities,
                                                 int state) {
        validateIdentity(gatewayIdentity, "网关身份无效，无法构建子设备生命周期消息");
        if (subDeviceIdentities == null || subDeviceIdentities.isEmpty()) {
            throw new IllegalArgumentException("子设备身份不能为空");
        }
        List<Map<String, Object>> subDevices = subDeviceIdentities.stream()
                .peek(identity -> validateIdentity(identity, "子设备身份无效"))
                .map(identity -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("identity", Map.of(
                            "productKey", identity.productKey(),
                            "deviceName", identity.deviceName()));
                    item.put("state", state);
                    return item;
                })
                .toList();
        return new LifecycleMessage(
                topicBuilder.build(gatewayIdentity, AlinkMethod.STATE_UPDATE, false),
                encodeBody(Map.of("subDevices", subDevices)));
    }

    /**
     * 解析或转换业务数据。
     */
    private byte[] encodeBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", MessageConstant.MESSAGE_TYPE_STATE_UPDATE);
        body.put("params", params);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("编码 Alink 生命周期消息失败", e);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateIdentity(CloudDeviceIdentity identity, String message) {
        if (identity == null || !identity.valid()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record LifecycleMessage(String topic, byte[] payload) {
    }
}
