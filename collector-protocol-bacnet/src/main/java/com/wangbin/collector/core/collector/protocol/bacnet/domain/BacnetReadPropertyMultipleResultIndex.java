package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetReadPropertyMultipleResultIndex {

    private final Map<String, BacnetReadPropertyMultipleResponse.PropertyValueResult> resultByKey;

    /**
     * 创建当前组件实例。
     */
    private BacnetReadPropertyMultipleResultIndex(
            Map<String, BacnetReadPropertyMultipleResponse.PropertyValueResult> resultByKey) {
        this.resultByKey = resultByKey;
    }

    /**
     * 创建并返回业务对象。
     */
    public static BacnetReadPropertyMultipleResultIndex from(BacnetReadPropertyMultipleResponse response) {
        Map<String, BacnetReadPropertyMultipleResponse.PropertyValueResult> index = new HashMap<>();
        List<BacnetReadPropertyMultipleResponse.ReadAccessResult> results =
                response != null ? response.getResults() : null;
        if (results != null) {
            for (BacnetReadPropertyMultipleResponse.ReadAccessResult result : results) {
                if (result == null || result.getPropertyResults() == null) {
                    continue;
                }
                for (BacnetReadPropertyMultipleResponse.PropertyValueResult propertyResult : result.getPropertyResults()) {
                    if (propertyResult == null) {
                        continue;
                    }
                    index.put(key(result.getObjectType(), result.getObjectInstance(),
                            propertyResult.getPropertyIdentifier(), propertyResult.getArrayIndex()), propertyResult);
                }
            }
        }
        return new BacnetReadPropertyMultipleResultIndex(index);
    }

    /**
     * 执行当前业务逻辑。
     */
    public BacnetReadPropertyMultipleResponse.PropertyValueResult get(BacnetAddress address) {
        return resultByKey.get(key(BacnetObjectType.fromId(address.getObjectTypeId()), address.getInstanceNumber(),
                BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()), address.getArrayIndex()));
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String key(BacnetObjectType objectType,
                              int objectInstance,
                              BacnetPropertyIdentifier propertyIdentifier,
                              Integer arrayIndex) {
        return objectType.getId() + ":" + objectInstance + "." + propertyIdentifier.getId()
                + (arrayIndex != null ? "[" + arrayIndex + "]" : "");
    }
}
