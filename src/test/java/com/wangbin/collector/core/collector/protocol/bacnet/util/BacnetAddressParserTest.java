package com.wangbin.collector.core.collector.protocol.bacnet.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacnetAddressParserTest {

    @Test
    void shouldParseBasicPresentValueAddress() {
        DataPoint point = point("analogInput:1.presentValue");

        BacnetAddress address = BacnetAddressParser.parse(point);

        assertEquals("analogInput", address.getObjectType());
        assertEquals(1, address.getInstanceNumber());
        assertEquals("presentValue", address.getPropertyIdentifier());
        assertEquals("analogInput:1.presentValue", address.getCanonicalAddress());
        assertEquals("AUTO", address.getDriverDataType());
    }

    @Test
    void shouldParseArrayIndexFromAddress() {
        DataPoint point = point("device:1001.objectList[12]");

        BacnetAddress address = BacnetAddressParser.parse(point);

        assertEquals("device", address.getObjectType());
        assertEquals(1001, address.getInstanceNumber());
        assertEquals("objectList", address.getPropertyIdentifier());
        assertEquals(12, address.getArrayIndex());
    }

    @Test
    void shouldResolveArrayIndexAndDriverTypeFromAdditionalConfig() {
        DataPoint point = point("analogInput:7.presentValue");
        point.setAdditionalConfig(ext(
                "arrayIndex", 3,
                "driverDataType", "real"
        ));

        BacnetAddress address = BacnetAddressParser.parse(point);

        assertEquals(3, address.getArrayIndex());
        assertEquals("REAL", address.getDriverDataType());
    }

    @Test
    void shouldRejectUnsupportedAddressFormat() {
        DataPoint point = point("presentValue");

        assertThrows(IllegalArgumentException.class, () -> BacnetAddressParser.parse(point));
    }

    @Test
    void shouldRejectNegativeArrayIndex() {
        DataPoint point = point("analogInput:1.presentValue");
        point.setAdditionalConfig(ext("arrayIndex", -1));

        assertThrows(IllegalArgumentException.class, () -> BacnetAddressParser.parse(point));
    }

    private DataPoint point(String address) {
        DataPoint point = new DataPoint();
        point.setAddress(address);
        point.setDataType("FLOAT");
        return point;
    }

    private Map<String, Object> ext(Object... entries) {
        Map<String, Object> extJson = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            extJson.put(entries[i].toString(), entries[i + 1]);
        }
        return extJson;
    }
}
