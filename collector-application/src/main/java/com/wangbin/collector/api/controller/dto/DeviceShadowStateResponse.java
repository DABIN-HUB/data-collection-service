package com.wangbin.collector.api.controller.dto;

import java.util.Map;

/**
 * 设备影子 state 外层结构响应，内部属性名由设备物模型动态决定。
 */
public record DeviceShadowStateResponse(Map<String, Object> reported,
                                        Map<String, Object> desired,
                                        Map<String, Object> delta,
                                        Map<String, Object> lastReported) {
}
