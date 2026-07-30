package com.wangbin.collector.core.collector.protocol.custom.codec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomRequestEncoderTest {

    @Test
    void shouldResolveControlledHexPlaceholders() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(new LinkedHashMap<>(Map.of(
                "readRequestTemplate", "0103${addressHex}0001",
                "writeRequestTemplate", "0106${addressHex}${valueHex}",
                "requestEncoding", "HEX")));
        DataPoint point = new DataPoint();
        point.setPointId("point-1");
        point.setAddress("BYTE:0:2");
        point.setDataType("UINT16");
        point.setAdditionalConfig(Map.of(
                "requestAddress", "16",
                "addressHexWidth", 4));

        assertEquals("010300100001", CustomFrameCodec.encodeHex(
                CustomRequestEncoder.encodeRead(point, connection)));
        assertEquals("010600101234", CustomFrameCodec.encodeHex(
                CustomRequestEncoder.encodeWrite(point, 0x1234, connection)));
    }
}
