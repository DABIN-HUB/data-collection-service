package com.wangbin.collector.api.filter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 访问日志治理配置
 */
@Data
@ConfigurationProperties(prefix = "logging.access")
public class AccessLogProperties {

    /**
     * 是否开启访问日志
     */
    private boolean enabled = true;

    /**
     * 需要纳入记录的路径，Ant 风格
     */
    private List<String> includePaths = new ArrayList<>(List.of("/api/**"));

    /**
     * 排除的路径，Ant 风格
     */
    private List<String> excludePaths = new ArrayList<>(Arrays.asList(
            "/health", "/actuator/**"));

    /**
     * 高风险接口配置，命中时以 WARN 级别打印
     */
    private List<RiskRule> highRiskRules = new ArrayList<>();

    /**
     * 查询字符串最大记录长度
     */
    private int maxQueryLength = 512;

    /**
     * 是否记录请求体大小（仅记录长度，不输出内容）
     */
    private boolean logBodySize = true;

    /**
     * 用于识别调用方的 Token 头
     */
    private String tokenHeader = "X-Collector-Token";

    /**
     * 自定义请求 ID 头，若不存在则自动生成
     */
    private String requestIdHeader = "X-Request-Id";

    /**
     * 需要透出的额外 Header
     */
    private List<String> additionalHeaders = new ArrayList<>();

    public void setIncludePaths(List<String> includePaths) {
        if (!CollectionUtils.isEmpty(includePaths)) {
            this.includePaths = includePaths;
        }
    }

    @Data
    public static class RiskRule {
        /**
         * HTTP 方法，缺省表示任意
         */
        private String method = "*";

        /**
         * 路径模式（Ant 风格）
         */
        private String pattern;
    }
}
