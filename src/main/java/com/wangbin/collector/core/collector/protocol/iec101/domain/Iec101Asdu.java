package com.wangbin.collector.core.collector.protocol.iec101.domain;

import java.util.List;

/**
 * IEC101 应用服务数据单元。
 */
public record Iec101Asdu(int typeId,
                         int causeOfTransmission,
                         boolean negativeConfirm,
                         boolean test,
                         int originatorAddress,
                         int commonAddress,
                         boolean sequence,
                         List<Iec101InformationObject> informationObjects) {

    public Iec101Asdu {
        informationObjects = informationObjects == null ? List.of() : List.copyOf(informationObjects);
    }
}
