package com.wangbin.collector.core.report.model.message;

import com.wangbin.collector.common.constant.MessageConstant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 属性上报消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PropertyMessage extends IoTMessage {
    /**
     * 创建当前组件实例。
     */
    public PropertyMessage() {
        setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_POST);
        setTimestamp(System.currentTimeMillis());
    }

    /**
     * 设置属性值。
     */
    public void setProperty(String name, Object value) {
        addParam(name, value);
    }
}