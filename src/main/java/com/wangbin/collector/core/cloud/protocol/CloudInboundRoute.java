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

    /**
     * 执行当前业务逻辑。
     */
    public static CloudInboundRoute ackReply(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.ACK_REPLY, method, true);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static CloudInboundRoute businessReply(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.BUSINESS_REPLY, method, true);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static CloudInboundRoute downlinkCommand(String method) {
        return new CloudInboundRoute(CloudInboundMessageType.DOWNLINK_COMMAND, method, false);
    }

    /**
     * 构造标准业务结果。
     */
    public static CloudInboundRoute ignored() {
        return new CloudInboundRoute(CloudInboundMessageType.IGNORED, null, false);
    }

    /**
     * 构造标准业务结果。
     */
    public boolean ignoredRoute() {
        return type == CloudInboundMessageType.IGNORED;
    }
}
