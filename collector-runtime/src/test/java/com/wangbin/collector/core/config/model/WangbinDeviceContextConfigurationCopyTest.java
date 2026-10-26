package com.wangbin.collector.core.config.model;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实配置快照不经 typed 告警 getter，也不共享嵌套可变值。 */
class WangbinDeviceContextConfigurationCopyTest {
    @Test
    void preservesRawAlarmWithUnknownNestedRuleFields() throws Exception {
        String raw = "[{\"type\":\"HIGH\",\"futureRule\":{\"decimal\":0.123456789012345678901234567890,\"items\":[null,{\"newField\":7}]}}]";
        DataPoint point = new DataPoint();
        point.setPointId("copy-point");
        point.setAlarmRule(raw);
        DeviceContext context = DeviceContext.of(device(), null, List.of(point));
        assertEquals(raw, raw(context.getDataPoints().get(0), "alarmRule"));
        assertEquals(raw, raw(context.copyDataPoints().get(0), "alarmRule"));
    }

    @Test
    void detachesNestedConfigurationWithoutLosingDecimalPrecision() {
        Map<String, Object> nested = new LinkedHashMap<>();
        BigDecimal decimal = new BigDecimal("0.123456789012345678901234567890");
        nested.put("decimal", decimal);
        nested.put("items", new ArrayList<>(List.of("before")));
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("future", nested);
        extension.put("nullable", null);
        DeviceInfo device = device();
        device.setAuthConfig(extension);
        device.setCreateTime(new Date(123));
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(extension);
        connection.setAuthParams(new LinkedHashMap<>(Map.of("code", "001")));
        DataPoint point = new DataPoint();
        point.setAdditionalConfig(extension);
        point.setAlarmRule(null);
        DeviceContext context = DeviceContext.of(device, connection, List.of(point));
        nested.put("decimal", BigDecimal.ZERO);
        device.getCreateTime().setTime(999);
        assertEquals(decimal, nested(context.getDeviceInfo().getAuthConfig()).get("decimal"));
        assertEquals(decimal, nested(context.getConnectionConfig().getExtJson()).get("decimal"));
        assertEquals(decimal, nested(context.getDataPoints().get(0).getAdditionalConfig()).get("decimal"));
        assertEquals(123, context.getDeviceInfo().getCreateTime().getTime());
        Map<String, Object> copiedNested = nested(context.copyDataPoints().get(0).getAdditionalConfig());
        copiedNested.put("decimal", BigDecimal.ONE);
        assertEquals(decimal, nested(context.getDataPoints().get(0).getAdditionalConfig()).get("decimal"));
        context.copyConnectionConfig().getAuthParams().put("code", "changed");
        assertEquals("001", context.getConnectionConfig().getAuthParams().get("code"));
        assertTrue(context.getDataPoints().get(0).getAdditionalConfig().containsKey("nullable"));
    }

    @Test
    void neverInvokesTypedAlarmGetterOnAnonymousRuntimeEntity() {
        DataPoint point = new DataPoint() {
            @Override
            public List<com.wangbin.collector.common.domain.entity.AlarmRule> getAlarmRule() {
                throw new AssertionError("不得读取 typed 告警规则");
            }
        };
        point.setAlarmRule("[{\"futureRule\":true}]");
        assertDoesNotThrow(() -> DeviceContext.of(device(), null, List.of(point)));
    }

    private DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("copy-device");
        return device;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> map) {
        return (Map<String, Object>) map.get("future");
    }

    private Object raw(Object bean, String name) throws Exception {
        Field field = DataPoint.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bean);
    }
}
