package com.wangbin.collector.core.collector.protocol.iec.domain;

import com.beanit.iec61850bean.Fc;
import lombok.Getter;

/**
 * IEC61850 地址描述。
 */
@Getter
public class Iec61850Address {

    private final String objectReference;
    private final Fc functionalConstraint;
    private final String original;

    /**
     * 创建当前组件实例。
     */
    public Iec61850Address(String objectReference, Fc functionalConstraint, String original) {
        this.objectReference = objectReference;
        this.functionalConstraint = functionalConstraint;
        this.original = original;
    }

    public String getCacheKey() {
        return objectReference + "@" + functionalConstraint;
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public String toString() {
        return original != null ? original : objectReference + "@" + functionalConstraint;
    }
}
