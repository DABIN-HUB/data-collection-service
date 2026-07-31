package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
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
