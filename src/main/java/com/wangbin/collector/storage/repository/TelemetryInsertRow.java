package com.wangbin.collector.storage.repository;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TDengine 遥测写入行参数，供同一子表批量 INSERT 复用单条写入列语义。
 */
@Getter
@RequiredArgsConstructor
public class TelemetryInsertRow {

    private final long eventTs;
    private final String pointKey;
    private final String pointId;
    private final String pointCode;
    private final String pointName;
    private final String valueText;
    private final String unit;
    private final Integer quality;
    private final Boolean success;
    private final String message;
    private final String rawJson;
    private final String processedJson;
    private final String metadataJson;
}
