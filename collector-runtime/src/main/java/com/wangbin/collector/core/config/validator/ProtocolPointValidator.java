package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.util.List;

/**
 * 协议点位写入配置前的校验扩展点；历史配置加载由运行期隔离处理。
 */
public interface ProtocolPointValidator {

    /** 当前协议是否由此校验器负责。 */
    boolean supports(DeviceInfo device);

    /** 校验候选点位，非法配置应抛出可定位的异常。 */
    void validate(List<DataPoint> points);
}
