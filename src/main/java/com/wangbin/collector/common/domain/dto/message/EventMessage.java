package com.wangbin.collector.common.domain.dto.message;

import com.wangbin.collector.common.constant.MessageConstant;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 事件上报消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EventMessage extends BaseMessage {

    private String deviceId;
    private String deviceName;
    private String eventType;
    private String eventLevel; // 事件级别，例如信息、警告、错误或严重。
    private String eventCode;
    private String eventMessage;
    private Map<String, Object> eventData;

    /**
     * 创建当前组件实例。
     */
    public EventMessage() {
        super();
    }

    /**
     * 创建当前组件实例。
     */
    public EventMessage(String deviceId, String eventType, String eventLevel,
                        String eventMessage, Map<String, Object> eventData) {
        super(MessageConstant.MESSAGE_TYPE_EVENT_POST, eventData);
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.eventLevel = eventLevel;
        this.eventMessage = eventMessage;
    }
}
