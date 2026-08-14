package com.wangbin.collector.core.report.inbound;

import com.wangbin.collector.core.cloud.protocol.CloudInboundMessageType;
import com.wangbin.collector.core.cloud.protocol.CloudInboundRoute;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * MQTT 入站消息分发器，只负责按云协议分类结果路由消息。
 */
@Slf4j
public class MqttInboundMessageDispatcher {

    private final CloudProtocolAdapter protocolAdapter;
    private final String replySuffix;
    private final MqttAckReplyHandler ackReplyHandler;
    private final MqttBusinessReplyService businessReplyService;
    private final MqttDownlinkCommandHandler downlinkCommandHandler;

    /**
     * 创建当前组件实例。
     */
    public MqttInboundMessageDispatcher(CloudProtocolAdapter protocolAdapter,
                                        String replySuffix,
                                        MqttAckReplyHandler ackReplyHandler,
                                        MqttBusinessReplyService businessReplyService,
                                        MqttDownlinkCommandHandler downlinkCommandHandler) {
        this.protocolAdapter = protocolAdapter;
        this.replySuffix = replySuffix == null || replySuffix.isBlank() ? "_reply" : replySuffix.trim();
        this.ackReplyHandler = ackReplyHandler;
        this.businessReplyService = businessReplyService;
        this.downlinkCommandHandler = downlinkCommandHandler;
    }

    /**
     * 处理当前业务流程。
     */
    public void dispatch(MqttInboundMessage message) {
        if (message == null || protocolAdapter == null) {
            return;
        }
        CloudInboundRoute route = protocolAdapter.classifyInbound(message.topic(), replySuffix);
        if (route.ackReply() && ackReplyHandler != null) {
            ackReplyHandler.handle(message);
        }
        if (route.type() == CloudInboundMessageType.ACK_REPLY) {
            return;
        }
        if (route.type() == CloudInboundMessageType.BUSINESS_REPLY) {
            if (businessReplyService != null) {
                businessReplyService.handle(message, route);
            }
            return;
        }
        if (route.type() == CloudInboundMessageType.DOWNLINK_COMMAND) {
            if (downlinkCommandHandler != null) {
                downlinkCommandHandler.handle(message, route);
            }
            return;
        }
        if (route.ignoredRoute()) {
            log.trace("忽略未识别 MQTT 入站消息：主题={}", message.topic());
        }
    }
}
