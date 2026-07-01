package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.Map;

@Value
@Builder
public class BacnetValue {

    Object value;
    String valueType;
    BacnetValueKind kind;
    @Builder.Default
    Map<String, Object> metadata = new LinkedHashMap<>();

    public boolean isComplex() {
        return kind != null && kind != BacnetValueKind.PRIMITIVE;
    }
}
