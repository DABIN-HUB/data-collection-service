package com.wangbin.collector.api.filter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 鉴权配置.
 */
@Data
@ConfigurationProperties(prefix = "collector.auth")
public class AuthProperties {

    private boolean enabled = true;
    private List<String> permitAllPaths = new ArrayList<>(List.of("/health", "/actuator/**"));
    private List<String> ipAllowList = new ArrayList<>();

    private String tokenHeader = "X-Collector-Token";
    private String serviceHeader = "X-Collector-Service";
    private String signatureHeader = "X-Collector-Signature";
    private String timestampHeader = "X-Collector-Timestamp";
    private String keyVersionHeader = "X-Collector-Key-Version";

    /**
     * 允许的最大时间偏移（秒），防止重放。
     */
    private long maxSkewSeconds = 300;

    /**
     * 运维操作令牌: token -> 描述.
     */
    private Map<String, String> opsTokens = new LinkedHashMap<>();

    /**
     * 服务之间调用凭据: serviceId -> 配置.
     */
    private Map<String, ServiceClient> serviceClients = new LinkedHashMap<>();

    @Data
    public static class ServiceClient {
        private boolean enabled = true;
        private boolean requireSignature = true;
        private boolean allowIpFallback = true;
        private long maxSkewSeconds = -1;
        private String defaultKey;
        private Map<String, String> keys = new LinkedHashMap<>();
        private List<String> allowIps = new ArrayList<>();

        public String resolveSecret(String requestedVersion) {
            if (!CollectionUtils.isEmpty(keys)) {
                if (StringUtils.hasText(requestedVersion) && keys.containsKey(requestedVersion)) {
                    return keys.get(requestedVersion);
                }
                if (StringUtils.hasText(defaultKey) && keys.containsKey(defaultKey)) {
                    return keys.get(defaultKey);
                }
                return keys.values().iterator().next();
            }
            return null;
        }

        public long resolveMaxSkew(long defaultSkewSeconds) {
            return maxSkewSeconds > 0 ? maxSkewSeconds : defaultSkewSeconds;
        }
    }
}
