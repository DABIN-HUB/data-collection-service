package com.wangbin.collector.core.cloud.protocol;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;

import java.io.IOException;
import java.util.List;

/**
 * 云平台协议适配器，只封装云厂商差异，主链路不感知具体协议。
 */
public interface CloudProtocolAdapter {

    String DEFAULT_PROVIDER = "alink";

    String provider();

    default List<String> aliases() {
        return List.of(provider());
    }

    String buildPublishTopic(ReportData data, ReportConfig config);

    default byte[] encodeReportData(ReportData data) {
        return encodeReportData(data, CloudPayloadOptions.defaults());
    }

    byte[] encodeReportData(ReportData data, CloudPayloadOptions options);

    CloudProtocolMessage decode(String topic, byte[] payload) throws IOException;

    List<String> downlinkTopicPaths();

    default List<String> ackMethods() {
        return MessageConstant.getAckMethods();
    }
}
