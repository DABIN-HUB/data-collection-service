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

    /**
     * 执行当前业务逻辑。
     */
    String provider();

    /**
     * 执行当前业务逻辑。
     */
    default List<String> aliases() {
        return List.of(provider());
    }

    /**
     * 创建并返回业务对象。
     */
    String buildPublishTopic(ReportData data, ReportConfig config);

    /**
     * 解析或转换业务数据。
     */
    default byte[] encodeReportData(ReportData data) {
        return encodeReportData(data, CloudPayloadOptions.defaults());
    }

    /**
     * 解析或转换业务数据。
     */
    byte[] encodeReportData(ReportData data, CloudPayloadOptions options);

    /**
     * 解析或转换业务数据。
     */
    CloudProtocolMessage decode(String topic, byte[] payload) throws IOException;

    /**
     * 执行当前业务逻辑。
     */
    List<String> downlinkTopicPaths();

    /**
     * 执行当前业务逻辑。
     */
    default List<String> businessReplyTopicPaths() {
        return List.of();
    }

    /**
     * 执行当前业务逻辑。
     */
    default List<String> ackMethods() {
        return MessageConstant.getAckMethods();
    }

    /**
     * 执行当前业务逻辑。
     */
    default CloudInboundRoute classifyInbound(String topic, String replySuffix) {
        String topicPath = extractTopicPath(topic);
        if (topicPath == null || topicPath.isBlank()) {
            return CloudInboundRoute.ignored();
        }

        String normalizedReplySuffix = normalizeReplySuffix(replySuffix);
        if (topicPath.endsWith(normalizedReplySuffix)) {
            if (containsTopicPath(businessReplyTopicPaths(), topicPath)) {
                return CloudInboundRoute.businessReply(toMethod(topicPath.substring(
                        0, topicPath.length() - normalizedReplySuffix.length())));
            }
            String methodPath = topicPath.substring(0, topicPath.length() - normalizedReplySuffix.length());
            if (containsAckMethod(methodPath)) {
                return CloudInboundRoute.ackReply(toMethod(methodPath));
            }
            return CloudInboundRoute.ignored();
        }

        if (containsTopicPath(downlinkTopicPaths(), topicPath)) {
            return CloudInboundRoute.downlinkCommand(toMethod(topicPath));
        }
        return CloudInboundRoute.ignored();
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean containsAckMethod(String methodPath) {
        for (String method : ackMethods()) {
            if (MessageConstant.methodToTopicPath(method).equals(methodPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean containsTopicPath(List<String> paths, String topicPath) {
        if (paths == null || topicPath == null) {
            return false;
        }
        for (String path : paths) {
            if (topicPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析或转换业务数据。
     */
    private String extractTopicPath(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String normalized = topic.replace('\\', '/').trim();
        String[] segments = normalized.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (!"sys".equals(segments[i])) {
                continue;
            }
            if (segments.length <= i + 3) {
                return null;
            }
            StringBuilder path = new StringBuilder();
            for (int j = i + 3; j < segments.length; j++) {
                if (segments[j] == null || segments[j].isBlank()) {
                    continue;
                }
                if (!path.isEmpty()) {
                    path.append('/');
                }
                path.append(segments[j]);
            }
            return path.toString();
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeReplySuffix(String replySuffix) {
        return replySuffix == null || replySuffix.isBlank() ? "_reply" : replySuffix.trim();
    }

    /**
     * 解析或转换业务数据。
     */
    private String toMethod(String topicPath) {
        return topicPath == null ? null : topicPath.replace('/', '.');
    }
}
