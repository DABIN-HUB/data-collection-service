package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 保存新 FINS 点位时复用运行期地址解析器，不改变历史配置加载行为。
 */
@Component
public class OmronFinsProtocolPointValidator implements ProtocolPointValidator {

    @Override
    public boolean supports(DeviceInfo device) {
        if (device == null || device.getProtocolType() == null) {
            return false;
        }
        String protocol = device.getProtocolType().trim().toUpperCase(Locale.ROOT);
        return "OMRON_FINS".equals(protocol) || "FINS".equals(protocol) || "OMRONFINS".equals(protocol);
    }

    @Override
    public void validate(List<DataPoint> points) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                FinsAddressParser.parse(point);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("FINS 点位配置无效, pointId=" + point.getPointId()
                        + ", address=" + point.getAddress() + ": " + exception.getMessage(), exception);
            }
        }
    }
}
