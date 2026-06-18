package com.wangbin.collector.core.config.loader;

import com.wangbin.collector.common.domain.entity.ApiResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "collector.config", name = "loader", havingValue = "remote", matchIfMissing = true)
public class RemoteConfigLoader implements ConfigLoader {

    @Value("${collector.config.yun-url:http://localhost:8080/admin-api}")
    private String runUrl;

    @Value("${collector.config.tenant-id:1}")
    private String tenantId;

    @Value("${collector.config.service-id:collector-1}")
    private String serviceId;

    @Value("${collector.config.api-token:}")
    private String apiToken;

    private final RestTemplate restTemplate;

    public RemoteConfigLoader(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<DeviceInfo> loadAllDevices() {
        String url = runUrl + "/iot/collector/config/devices?serviceId=" + serviceId;
        try {
            ResponseEntity<ApiResponse<List<DeviceInfo>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<DeviceInfo> devices = response.getBody().getData();
                return devices != null ? devices : Collections.emptyList();
            }
            log.warn("load remote devices failed, status={}", response.getStatusCode());
        } catch (Exception e) {
            log.error("加载远程设备配置失败,不影响现有采集，url:{}", url);
        }
        return Collections.emptyList();
    }

    @Override
    public DeviceInfo loadDevice(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/device/" + deviceId;
            ResponseEntity<ApiResponse<DeviceInfo>> response = restTemplate.exchange(
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody().getData();
            }
            log.warn("load remote device failed, deviceId={}, status={}", deviceId, response.getStatusCode());
        } catch (Exception e) {
            log.error("load remote device failed, deviceId={}", deviceId, e);
        }
        return null;
    }

    @Override
    public List<DataPoint> loadDataPoints(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/points/" + deviceId;
            ResponseEntity<ApiResponse<List<DataPoint>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<DataPoint> points = response.getBody().getData();
                return points != null ? points : Collections.emptyList();
            }
            log.warn("load remote points failed, deviceId={}, status={}", deviceId, response.getStatusCode());
        } catch (Exception e) {
            log.error("load remote points failed, deviceId={}", deviceId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public DeviceConnection loadConnectionConfig(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/connection/" + deviceId;
            ResponseEntity<ApiResponse<DeviceConnection>> response = restTemplate.exchange(
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK) {
                return Objects.requireNonNull(response.getBody()).getData();
            }
            log.warn("load remote connection failed, deviceId={}, status={}", deviceId, response.getStatusCode());
        } catch (Exception e) {
            log.error("load remote connection failed, deviceId={}", deviceId, e);
        }
        return null;
    }

    private HttpEntity<String> createAuthRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Token", getApiToken());
        headers.set("tenant-id", tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private String getApiToken() {
        if (apiToken != null && !apiToken.trim().isEmpty()) {
            return apiToken.trim();
        }
        String envToken = System.getenv("COLLECTOR_API_TOKEN");
        if (envToken != null && !envToken.trim().isEmpty()) {
            return envToken.trim();
        }
        log.warn("API token not configured, using empty value");
        return "";
    }
}
