package com.wangbin.collector.core.collector.protocol.knx.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.knx.domain.KnxAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnxAddressParserTest {

    @Test
    void shouldParseThreeLevelGroupAddressWithExplicitDpt() {
        KnxAddress address = KnxAddressParser.parse(point("1/2/3:DPT1.001", "BOOLEAN", Map.of()));

        assertEquals("1/2/3", address.getGroupAddress());
        assertEquals("1/2/3:DPT1.001", address.getPlc4xAddress());
        assertEquals(3, address.getLevels());
        assertTrue(address.hasDpt());
    }

    @Test
    void shouldAppendDptFromAdditionalConfig() {
        KnxAddress address = KnxAddressParser.parse(point("1/200", "FLOAT", Map.of("dpt", "9.001")));

        assertEquals("1/200:DPT9.001", address.getPlc4xAddress());
        assertEquals("DPT9.001", address.getDptId());
        assertEquals(2, address.getLevels());
    }

    @Test
    void shouldRejectWildcardGroupAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> KnxAddressParser.parse(point("1/*/3", "BOOLEAN", Map.of())));
    }

    private DataPoint point(String address, String dataType, Map<String, Object> additionalConfig) {
        DataPoint point = new DataPoint();
        point.setPointId(address);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setAdditionalConfig(new LinkedHashMap<>(additionalConfig));
        return point;
    }
}
