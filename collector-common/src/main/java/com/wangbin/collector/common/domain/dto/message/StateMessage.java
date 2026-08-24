package com.wangbin.collector.common.domain.dto.message;

import com.wangbin.collector.common.constant.MessageConstant;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 状态上报消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StateMessage extends BaseMessage {

    private String deviceId;
    private String deviceName;
    private String status; // 状态值，例如在线、离线或异常。
    private String reason;
    private Map<String, Object> attributes;

    /**
     * 创建当前组件实例。
     */
    public StateMessage() {
        super();
    }

    /**
     * 创建当前组件实例。
     */
    public StateMessage(String deviceId, String status) {
        super(MessageConstant.MESSAGE_TYPE_STATE_UPDATE, null);
        this.deviceId = deviceId;
        this.status = status;
    }

    /**
     * 创建当前组件实例。
     */
    public StateMessage(String deviceId, String status, String reason) {
        this(deviceId, status);
        this.reason = reason;
    }
}
