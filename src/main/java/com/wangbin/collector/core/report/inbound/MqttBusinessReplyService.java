package com.wangbin.collector.core.report.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.protocol.CloudInboundRoute;
import com.wangbin.collector.core.cloud.register.CloudSubDeviceRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * MQTT 平台业务回执服务，处理带业务结果的 _reply 消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttBusinessReplyService {

    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private CloudSubDeviceRegisterService cloudSubDeviceRegisterService;

    /**
     * 处理当前业务流程。
     */
    public MqttBusinessReplyResult handle(MqttInboundMessage message, CloudInboundRoute route) {
        if (message == null || route == null) {
            return MqttBusinessReplyResult.ignored(null);
        }
        if (!MessageConstant.MESSAGE_TYPE_AUTH_REGISTER_SUB.equals(route.method())) {
            log.trace("忽略未支持的 MQTT 业务回执：method={} 主题={}", route.method(), message.topic());
            return MqttBusinessReplyResult.ignored(route.method());
        }
        return handleSubDeviceRegisterReply(message, route.method());
    }

    /**
     * 处理当前业务流程。
     */
    private MqttBusinessReplyResult handleSubDeviceRegisterReply(MqttInboundMessage message, String method) {
        if (cloudSubDeviceRegisterService == null) {
            log.warn("子设备动态注册回执服务未初始化，无法处理 MQTT 业务回执：主题={}", message.topic());
            return MqttBusinessReplyResult.failure(method, "sub device register service unavailable");
        }
        try {
            JsonNode root = objectMapper.readTree(message.payload());
            Map<String, Object> data = cloudSubDeviceRegisterService.applyRegisterReply(root);
            log.info("子设备动态注册回执处理完成：主题={} registered={} total={}",
                    message.topic(), data.get("registered"), data.get("total"));
            return MqttBusinessReplyResult.success(method, data);
        } catch (Exception e) {
            log.warn("处理子设备动态注册回执失败：主题={} err={}", message.topic(), e.getMessage());
            return MqttBusinessReplyResult.failure(method, e.getMessage());
        }
    }
}
