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
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "collector.config", name = "loader", havingValue = "remote", matchIfMissing = true)
public class RemoteConfigLoader implements ConfigLoader {

    private static final String API_TOKEN_HEADER = "X-API-Token";
    private static final String TENANT_ID_HEADER = "tenant-id";

    @Value("${collector.config.yun-url:http://localhost:8080/admin-api}")
    private String runUrl;

    @Value("${collector.config.tenant-id:1}")
    private String tenantId;

    @Value("${collector.config.service-id:collector-1}")
    private String serviceId;

    @Value("${collector.config.api-token:}")
    private String apiToken;

    private final RestTemplate restTemplate;

    /**
     * 创建当前组件实例。
     */
    public RemoteConfigLoader(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public List<DeviceInfo> loadAllDevices() {
        String url = runUrl + "/iot/collector/config/devices?serviceId=" + serviceId;
        try {
            ResponseEntity<ApiResponse<List<DeviceInfo>>> response = restTemplate.exchange(
                    /**
                     * 创建并返回业务对象。
                     */
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<DeviceInfo> devices = response.getBody().getData();
                return devices != null ? devices : Collections.emptyList();
            }
            throw new ConfigLoadException("远程设备配置响应异常，状态码: " + response.getStatusCode());
        } catch (Exception exception) {
            if (exception instanceof ConfigLoadException configLoadException) {
                throw configLoadException;
            }
            throw new ConfigLoadException("加载远程设备配置失败，继续保留最后有效配置", exception);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public DeviceInfo loadDevice(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/device/" + deviceId;
            ResponseEntity<ApiResponse<DeviceInfo>> response = restTemplate.exchange(
                    /**
                     * 创建并返回业务对象。
                     */
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody().getData();
            }
            throw new ConfigLoadException("远程设备配置响应异常，设备=" + deviceId
                    + "，状态码=" + response.getStatusCode());
        } catch (HttpClientErrorException.NotFound exception) {
            return null;
        } catch (Exception exception) {
            if (exception instanceof ConfigLoadException configLoadException) {
                throw configLoadException;
            }
            throw new ConfigLoadException("加载远程设备配置失败，设备=" + deviceId, exception);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public List<DataPoint> loadDataPoints(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/points/" + deviceId;
            ResponseEntity<ApiResponse<List<DataPoint>>> response = restTemplate.exchange(
                    /**
                     * 创建并返回业务对象。
                     */
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<DataPoint> points = response.getBody().getData();
                return points != null ? points : Collections.emptyList();
            }
            throw new ConfigLoadException("远程点位配置响应异常，设备=" + deviceId
                    + "，状态码=" + response.getStatusCode());
        } catch (Exception exception) {
            if (exception instanceof ConfigLoadException configLoadException) {
                throw configLoadException;
            }
            throw new ConfigLoadException("加载远程点位配置失败，设备=" + deviceId, exception);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public DeviceConnection loadConnectionConfig(String deviceId) {
        try {
            String url = runUrl + "/iot/collector/config/connection/" + deviceId;
            ResponseEntity<ApiResponse<DeviceConnection>> response = restTemplate.exchange(
                    /**
                     * 创建并返回业务对象。
                     */
                    url, HttpMethod.GET, createAuthRequest(), new ParameterizedTypeReference<>() {
                    });
            if (response.getStatusCode() == HttpStatus.OK) {
                return Objects.requireNonNull(response.getBody()).getData();
            }
            throw new ConfigLoadException("远程连接配置响应异常，设备=" + deviceId
                    + "，状态码=" + response.getStatusCode());
        } catch (Exception exception) {
            if (exception instanceof ConfigLoadException configLoadException) {
                throw configLoadException;
            }
            throw new ConfigLoadException("加载远程连接配置失败，设备=" + deviceId, exception);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private HttpEntity<String> createAuthRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(API_TOKEN_HEADER, getApiToken());
        headers.set(TENANT_ID_HEADER, tenantId);
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
        log.warn("远程配置接口令牌未配置，将使用空令牌");
        return "";
    }
}
