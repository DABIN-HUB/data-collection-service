package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 设备控制接口统一响应。
 *
 * @param <T> 数据负载类型
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceControllerResponse<T> {

    /**
     * 业务状态，例如 success 或 error。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 固定结构或动态状态数据负载。
     */
    private T data;

    /**
     * 当前响应的数据数量。
     */
    private Integer count;

    /**
     * 设备是否正在运行。
     */
    private Boolean running;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;

    /**
     * 构建设备控制成功响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param message 提示信息
     * @return 设备控制响应
     */
    public static DeviceControllerResponse<Object> success(String deviceId, String message) {
        return base(deviceId, "success", message);
    }

    /**
     * 构建设备控制失败响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param message 提示信息
     * @return 设备控制响应
     */
    public static DeviceControllerResponse<Object> error(String deviceId, String message) {
        return base(deviceId, "error", message);
    }

    /**
     * 构建基础设备控制响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param status 业务状态
     * @param message 提示信息
     * @return 设备控制响应
     */
    public static DeviceControllerResponse<Object> base(String deviceId, String status, String message) {
        return DeviceControllerResponse.builder()
                .deviceId(deviceId)
                .status(status)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建带数据负载的成功响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param data 数据负载
     * @param <T> 数据负载类型
     * @return 设备控制响应
     */
    public static <T> DeviceControllerResponse<T> successData(String deviceId, T data) {
        return DeviceControllerResponse.<T>builder()
                .deviceId(deviceId)
                .status("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
