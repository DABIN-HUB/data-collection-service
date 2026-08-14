package com.wangbin.collector.core.report.inbound;

import com.wangbin.collector.core.cloud.protocol.CloudInboundRoute;
import com.wangbin.collector.core.report.downlink.MqttDownlinkResult;
import com.wangbin.collector.core.report.downlink.MqttDownlinkService;
import lombok.extern.slf4j.Slf4j;

/**
 * MQTT 下行命令处理器，只处理平台主动下发的命令并发送响应。
 */
@Slf4j
public class MqttDownlinkCommandHandler {

    private final MqttDownlinkService downlinkService;
    private final MqttDownlinkResponsePublisher responsePublisher;
    private final String replySuffix;

    /**
     * 创建当前组件实例。
     */
    public MqttDownlinkCommandHandler(MqttDownlinkService downlinkService,
                                      MqttDownlinkResponsePublisher responsePublisher,
                                      String replySuffix) {
        this.downlinkService = downlinkService;
        this.responsePublisher = responsePublisher;
        this.replySuffix = replySuffix == null || replySuffix.isBlank() ? "_reply" : replySuffix.trim();
    }

    /**
     * 处理当前业务流程。
     */
    public void handle(MqttInboundMessage message, CloudInboundRoute route) {
        if (downlinkService == null || message == null || message.payload().length == 0) {
            return;
        }
        MqttDownlinkResult result = downlinkService.handle(message.topic(), message.payload(), message.cloudProvider());
        if (result == null || !result.isResponseRequired()) {
            return;
        }
        String replyTopic = resolveReplyTopic(message.topic());
        if (replyTopic == null || replyTopic.isBlank() || responsePublisher == null) {
            return;
        }
        try {
            int qos = Math.max(0, Math.min(1, message.qos()));
            responsePublisher.publish(replyTopic, downlinkService.buildResponsePayload(result), qos);
            if (result.getCode() != 0) {
                log.warn("MQTT 下行命令执行失败，已回复平台：method={} 主题={} 状态码={} msg={}",
                        route != null ? route.method() : result.getMethod(),
                        replyTopic,
                        result.getCode(),
                        result.getMessage());
            }
        } catch (Exception e) {
            log.warn("MQTT 下行响应发布失败：主题={} err={}", replyTopic, e.getMessage());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveReplyTopic(String inboundTopic) {
        if (inboundTopic == null || inboundTopic.isBlank() || inboundTopic.endsWith(replySuffix)) {
            return null;
        }
        return inboundTopic + replySuffix;
    }
}
