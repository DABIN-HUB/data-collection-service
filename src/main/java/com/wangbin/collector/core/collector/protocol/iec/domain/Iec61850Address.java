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

    public Iec61850Address(String objectReference, Fc functionalConstraint, String original) {
        this.objectReference = objectReference;
        this.functionalConstraint = functionalConstraint;
        this.original = original;
    }

    public String getCacheKey() {
        return objectReference + "@" + functionalConstraint;
    }

    @Override
    public String toString() {
        return original != null ? original : objectReference + "@" + functionalConstraint;
    }
}
