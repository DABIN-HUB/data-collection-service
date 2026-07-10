package com.wangbin.collector.core.cloud.protocol;

/**
 * 云平台 MQTT 入站消息类型。
 */
public enum CloudInboundMessageType {

    /**
     * 平台对上行消息的业务 ACK。
     */
    ACK_REPLY,

    /**
     * 平台业务回执，既可能是 ACK，也包含业务结果。
     */
    BUSINESS_REPLY,

    /**
     * 平台主动下发命令。
     */
    DOWNLINK_COMMAND,

    /**
     * 当前协议不处理的消息。
     */
    IGNORED
}
