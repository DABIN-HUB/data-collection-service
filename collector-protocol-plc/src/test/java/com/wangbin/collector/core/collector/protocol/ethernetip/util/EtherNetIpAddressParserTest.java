package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import org.apache.plc4x.java.eip.base.tag.EipTag;
import org.apache.plc4x.java.eip.readwrite.CIPDataTypeCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtherNetIpAddressParserTest {

    @Test
    void symbolicAddressUsesActualPlc4xTypeThenCountGrammar() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("TagArray[2]", "LONG", Map.of("arraySize", 3)));
        assertEquals("TagArray[2]:DINT:3", address.getPlc4xAddress());
        assertEquals("TagArray[2]", EipTag.of(address.getPlc4xAddress()).getTag());
        assertEquals(CIPDataTypeCode.DINT, EipTag.of(address.getPlc4xAddress()).getType());
        assertEquals(3, EipTag.of(address.getPlc4xAddress()).getElementNb());
        assertFalse(address.isScalar());
    }

    @Test
    void rawPlc4xAddressIsPreservedAndNotOverriddenByPlatformType() {
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point("%Tag[1]:UINT:4", "FLOAT", Map.of()));
        assertEquals("%Tag[1]:UINT:4", address.getPlc4xAddress());
        assertEquals("%Tag[1]", address.getTagName());
        assertEquals("UINT", address.getBasePlcType());
        assertEquals(4, address.getArraySize());
    }

    @Test
    void plainTagInfersPlatformOrExplicitDriverType() {
        assertEquals("Tag1:UINT", EtherNetIpAddressParser.parse(point("Tag1", "WORD", Map.of())).getPlc4xAddress());
        assertEquals("Tag1:WORD", EtherNetIpAddressParser.parse(point("Tag1", "FLOAT", Map.of("driverDataType", "WORD"))).getPlc4xAddress());
    }

    @Test
    void rejectsArrayCountConflictsAndZeroOrOverflow() {
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse(point("Tag:DINT:3", "LONG", Map.of("arraySize", 4))));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse(point("Tag:DINT:0", "LONG", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse(point("Tag:DINT:2147483648", "LONG", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse(point("Tag:DINT:65536", "LONG", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse(point("Tag[2]:DINT:2", "LONG", Map.of("arrayIndex", 3))));
    }

    @Test
    void rejectsFormatsNotAcceptedByActualPlc4xDriver() {
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse("Program:Main.Tag:DINT:3"));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse("Tag:DINT[3]"));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse("%Tag:3:DINT"));
        assertThrows(IllegalArgumentException.class, () -> EtherNetIpAddressParser.parse("Tag:NOT_A_TYPE"));
    }

    @Test
    void resolverUsesParsedAddressBeforeConflictingPointType() {
        DataPoint point = point("Tag:UINT:2", "FLOAT", Map.of("driverDataType", "SINT"));
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point);
        assertEquals(EtherNetIpPlcType.UINT, EtherNetIpPlcTypeResolver.INSTANCE.resolveOrNull(point, address));
        assertEquals(EtherNetIpPlcType.SINT, EtherNetIpPlcTypeResolver.INSTANCE.resolveOrNull(point, null));
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
