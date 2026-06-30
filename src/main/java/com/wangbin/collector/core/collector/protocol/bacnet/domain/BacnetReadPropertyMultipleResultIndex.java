package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BacnetReadPropertyMultipleResultIndex {

    private final Map<String, BacnetReadPropertyMultipleResponse.PropertyValueResult> resultByKey;

    private BacnetReadPropertyMultipleResultIndex(
            Map<String, BacnetReadPropertyMultipleResponse.PropertyValueResult> resultByKey) {
        this.resultByKey = resultByKey;
    }

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

    public BacnetReadPropertyMultipleResponse.PropertyValueResult get(BacnetAddress address) {
        return resultByKey.get(key(BacnetObjectType.fromId(address.getObjectTypeId()), address.getInstanceNumber(),
                BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()), address.getArrayIndex()));
    }

    private static String key(BacnetObjectType objectType,
                              int objectInstance,
                              BacnetPropertyIdentifier propertyIdentifier,
                              Integer arrayIndex) {
        return objectType.getId() + ":" + objectInstance + "." + propertyIdentifier.getId()
                + (arrayIndex != null ? "[" + arrayIndex + "]" : "");
    }
}
