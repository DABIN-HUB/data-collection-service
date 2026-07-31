package com.wangbin.collector.core.cloud.ota;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OTA 升级任务服务，负责接收云端升级指令并维护本地任务状态。
 */
@Service
public class CloudOtaService {

    private final ConcurrentMap<String, OtaTask> tasks = new ConcurrentHashMap<>();

    /**
     * 创建并返回业务对象。
     */
    public Map<String, Object> createTask(String deviceId, JsonNode params) {
        String taskId = firstText(params, "taskId", "jobId", "otaJobId");
        if (!StringUtils.hasText(taskId)) {
            taskId = UUID.randomUUID().toString();
        }
        OtaTask task = new OtaTask(
                taskId,
                deviceId,
                firstText(params, "version", "targetVersion"),
                firstText(params, "url", "fileUrl", "firmwareUrl"),
                firstText(params, "sign", "digest", "md5", "sha256"),
                longValue(params, "size", "fileSize"),
                "CREATED",
                System.currentTimeMillis(),
                null);
        tasks.put(task.taskId(), task);
        return task.toMap();
    }

    /**
     * 更新或刷新业务状态。
     */
    public Map<String, Object> updateProgress(String taskId, String status, Integer progress, String message) {
        OtaTask existing = tasks.get(taskId);
        if (existing == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("status", status);
            data.put("progress", progress);
            data.put("message", message);
            return data;
        }
        OtaTask updated = new OtaTask(
                existing.taskId(),
                existing.deviceId(),
                existing.version(),
                existing.fileUrl(),
                existing.digest(),
                existing.fileSize(),
                StringUtils.hasText(status) ? status : existing.status(),
                existing.createdAt(),
                progress);
        tasks.put(taskId, updated);
        Map<String, Object> data = updated.toMap();
        if (StringUtils.hasText(message)) {
            data.put("message", message);
        }
        return data;
    }

    public OtaTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Long longValue(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.longValue();
            }
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                // 云端不同厂商可能下发字符串，解析失败时忽略该字段。
            }
        }
        return null;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record OtaTask(
            String taskId,
            String deviceId,
            String version,
            String fileUrl,
            String digest,
            Long fileSize,
            String status,
            long createdAt,
            Integer progress) {

        /**
         * 解析或转换业务数据。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("deviceId", deviceId);
            data.put("version", version);
            data.put("fileUrl", fileUrl);
            data.put("digest", digest);
            data.put("fileSize", fileSize);
            data.put("status", status);
            data.put("createdAt", createdAt);
            if (progress != null) {
                data.put("progress", progress);
            }
            return data;
        }
    }
}
