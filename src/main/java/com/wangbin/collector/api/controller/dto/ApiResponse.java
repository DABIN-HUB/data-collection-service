package com.wangbin.collector.api.controller.dto;

/**
 * 管理接口统一响应。
 */
public record ApiResponse<T>(String status,
                             String message,
                             T data,
                             long timestamp) {

    /**
     * 构造标准业务结果。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("success", message, data, System.currentTimeMillis());
    }

    /**
     * 构造标准业务结果。
     */
    public static <T> ApiResponse<T> error(String message, T data) {
        return new ApiResponse<>("error", message, data, System.currentTimeMillis());
    }
}
