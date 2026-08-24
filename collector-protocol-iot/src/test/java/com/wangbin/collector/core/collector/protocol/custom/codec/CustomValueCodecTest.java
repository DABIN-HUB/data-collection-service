package com.wangbin.collector.core.collector.protocol.custom.codec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomValueCodecTest {

    @Test
    void shouldDecodeByteBitAndJsonAddresses() throws Exception {
        DataPoint intPoint = point("BYTE:1:2", "UINT16", Map.of("byteOrder", "BIG_ENDIAN"));
        DataPoint bitPoint = point("BIT:0:3", "BOOLEAN", Map.of());
        DataPoint jsonPoint = point("JSON:$.data.values[1]", "FLOAT", Map.of());

        assertEquals(258, CustomValueCodec.decode(new byte[]{0x00, 0x01, 0x02}, intPoint));
        assertTrue((Boolean) CustomValueCodec.decode(new byte[]{0x08}, bitPoint));
        assertEquals(12.5D, CustomValueCodec.decode(
                """
                        {"data":{"values":[3,12.5]}}
                        """.trim().getBytes(), jsonPoint));
    }

    @Test
    void shouldEncodeValueWithConfiguredByteOrder() {
        DataPoint point = point("BYTE:0:2", "UINT16", Map.of("byteOrder", "LITTLE_ENDIAN"));

        assertEquals("3412", CustomFrameCodec.encodeHex(CustomValueCodec.encode(0x1234, point)));
    }

    private DataPoint point(String address, String dataType, Map<String, Object> additionalConfig) {
        DataPoint point = new DataPoint();
        point.setPointId("point-1");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setAdditionalConfig(additionalConfig);
        return point;
    }
}
