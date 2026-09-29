package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpAddressParser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 在保存新配置时用 PLC4X 0.13.0 的语法和实际编解码能力检查 Logix 点位。
 */
@Component
public class EtherNetIpProtocolPointValidator implements ProtocolPointValidator {
    private static final Set<String> SCALAR_TYPES = Set.of("BOOL", "SINT", "INT", "DINT", "LINT", "REAL", "LREAL");

    @Override
    public boolean supports(DeviceInfo device) {
        if (device == null || device.getProtocolType() == null) {
            return false;
        }
        String protocol = device.getProtocolType().trim().toUpperCase(Locale.ROOT);
        return Set.of("ETHERNET_IP", "EIP", "LOGIX", "AB_ETH").contains(protocol);
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
                EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point);
                if (address.isScalar() && !SCALAR_TYPES.contains(address.getBasePlcType())) {
                    throw new IllegalArgumentException("PLC4X 0.13.0 has no reliable EtherNet/IP scalar codec for "
                            + address.getBasePlcType());
                }
                if (!address.isScalar()) {
                    // 0.13.0 读请求固定读取 1 个元素，写入仅分配单元素缓冲区。
                    throw new IllegalArgumentException("PLC4X 0.13.0 does not support EtherNet/IP array read or write");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("EtherNet/IP 点位配置无效, pointId=" + point.getPointId()
                        + ", address=" + point.getAddress() + ": " + exception.getMessage(), exception);
            }
        }
    }
}
