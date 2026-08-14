package com.wangbin.collector.core.collector.protocol.snmp.domain;

import lombok.Getter;

/**
 * SNMP 地址包装.
 */
@Getter
public class SnmpAddress {
    private final String oid;
    private final SnmpDataType dataType;

    /**
     * 创建当前组件实例。
     */
    public SnmpAddress(String oid, SnmpDataType dataType) {
        if (oid == null || oid.isBlank()) {
            throw new IllegalArgumentException("SNMP OID不能为空");
        }
        this.oid = oid;
        this.dataType = dataType != null ? dataType : SnmpDataType.AUTO;
    }

    /**
     * 执行当前业务逻辑。
     */
    public String cacheKey() {
        return oid + "@" + dataType;
    }
}
