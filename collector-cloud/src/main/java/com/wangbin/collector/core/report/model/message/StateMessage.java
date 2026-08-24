package com.wangbin.collector.core.report.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

/**
 * 设备状态消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StateMessage extends IoTMessage {
    private Map<String, Object> state;

    /**
     * 创建当前组件实例。
     */
    public StateMessage() {
        setMethod("thing.state.update");
    }
}