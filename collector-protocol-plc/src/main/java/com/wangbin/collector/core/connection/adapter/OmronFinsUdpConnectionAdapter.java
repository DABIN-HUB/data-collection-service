package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

/** 历史 UDP 类名兼容入口；实际模式由 OmronFinsConnectionAdapter 按配置选择。 */
public class OmronFinsUdpConnectionAdapter extends OmronFinsConnectionAdapter {
    public OmronFinsUdpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }
}
