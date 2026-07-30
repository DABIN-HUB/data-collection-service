package com.wangbin.collector.core.cloud.protocol;

/**
 * 云平台 MQTT 入站消息路由结果。
 */
public record CloudInboundRoute(
        CloudInboundMessageType type,
        String method,
        boolean ackReply) {

    public CloudInboundRoute {
        type = type == null ? CloudInboundMessageType.IGNORED : type;
    }

    public static CloudInboundRoute ackReply(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.ACK_REPLY, method, true);
    }

    public static CloudInboundRoute businessReply(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.BUSINESS_REPLY, method, true);
    }

    public static CloudInboundRoute downlinkCommand(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.DOWNLINK_COMMAND, method, false);
    }

    public static CloudInboundRoute ignored() {
        return new CloudInboundRoute(CloudInboundMessageType.IGNORED, null, false);
    }

    public boolean ignoredRoute() {
        return type == CloudInboundMessageType.IGNORED;
    }
}
