package com.wangbin.collector.core.collector.protocol.knx.domain;

import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
@Value
public class KnxAddress {

    String rawAddress;
    String groupAddress;
    String plc4xAddress;
    int levels;
    String dptId;

    /**
     * 执行当前业务逻辑。
     */
    public boolean hasDpt() {
        return dptId != null && !dptId.isBlank();
    }

    public String getMainDpt() {
        if (!hasDpt()) {
            return null;
        }
        String dptBody = dptId.startsWith("DPT") ? dptId.substring(3) : dptId;
        int separatorIndex = dptBody.indexOf('.');
        return separatorIndex >= 0 ? dptBody.substring(0, separatorIndex) : dptBody;
    }
}
