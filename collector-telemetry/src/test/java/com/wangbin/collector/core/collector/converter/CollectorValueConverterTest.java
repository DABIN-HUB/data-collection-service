package com.wangbin.collector.core.collector.converter;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorValueConverterTest {

    private final CollectorValueConverter converter = new CollectorValueConverter();

    @Test
    void shouldConvertNumericStringAndBooleanRawValuesToDoubleByDefault() {
        DataPoint point = point(null);

        assertEquals(12.0D, converter.convertData(point, 12));
        assertEquals(13.5D, converter.convertData(point, "13.5"));
        assertEquals(1.0D, converter.convertData(point, true));
    }

    @Test
    void shouldApplyScaleOffsetAndPrecisionOnRead() {
        DataPoint point = point("scale");
        point.setScalingFactor(2.0D);
        point.setOffset(1.0D);
        point.setPrecision(2);

        assertEquals(3.47D, converter.convertData(point, "1.234"));
    }

    @Test
    void shouldConvertBooleanTypeAfterNumericNormalization() {
        DataPoint point = point("boolean");

        assertTrue((Boolean) converter.convertData(point, 1));
        assertFalse((Boolean) converter.convertData(point, 0));
        assertTrue((Boolean) converter.convertData(point, "1"));
        assertFalse((Boolean) converter.convertData(point, false));
    }

    @Test
    void shouldKeepLegacyCaseSensitiveDataTypeLookup() {
        DataPoint point = point("BOOLEAN");

        assertEquals(1.0D, converter.convertData(point, 1));
    }

    @Test
    void shouldReverseScaleAndOffsetForWriteUsingLegacyFormula() {
        DataPoint point = point(null);
        point.setScalingFactor(2.0D);
        point.setOffset(3.0D);

        assertEquals(3.5D, converter.convertDataForWrite(point, 13));
    }

    @Test
    void shouldReturnNullForNullReadAndWriteValues() {
        DataPoint point = point("boolean");

        assertNull(converter.convertData(point, null));
        assertNull(converter.convertDataForWrite(point, null));
    }

    @Test
    void shouldRejectUnparseableStringAndUnsupportedValueType() {
        IllegalArgumentException numberException = assertThrows(IllegalArgumentException.class,
                () -> converter.convertData(point(null), "abc"));
        assertEquals("无法转换为数字: abc", numberException.getMessage());

        IllegalArgumentException typeException = assertThrows(IllegalArgumentException.class,
                () -> converter.convertData(point(null), List.of("1")));
        assertTrue(typeException.getMessage().startsWith("不支持的数据类型: "));
    }

    @Test
    void shouldExposeSameConvertToDoubleSemanticsForPackageTests() {
        assertEquals(1.0D, converter.convertToDouble(true));
        assertEquals(2.5D, converter.convertToDouble("2.5"));
    }

    @Test
    void shouldValidateNullValidAndOutOfRangeValuesWithoutThrowing() {
        DataPoint point = point(null);
        point.setPointName("temperature");
        point.setMinValue(0.0D);
        point.setMaxValue(10.0D);

        assertDoesNotThrow(() -> converter.validateData(point, null));
        assertDoesNotThrow(() -> converter.validateData(point, 5));
        assertDoesNotThrow(() -> converter.validateData(point, 11));
    }

    private DataPoint point(String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setPointName("温度");
        point.setDataType(dataType);
        return point;
    }
}
