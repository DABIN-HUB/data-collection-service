package com.wangbin.collector.api.validation;

/**
 * 管理接口参数校验常量。
 */
public final class ApiValidationConstants {

    public static final String DEVICE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    public static final String DEVICE_ID_MESSAGE = "设备ID格式不正确";

    /**
     * 创建当前组件实例。
     */
    private ApiValidationConstants() {
    }
}
