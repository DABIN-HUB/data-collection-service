package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Value;

/** 后端运行时能力声明，供桌面端选择真实可用的功能与传输策略。 */
@Value
@Builder
public class SystemCapabilitiesResponse {
    Realtime realtime;
    History history;
    Control control;
    Shadow shadow;
    Cloud cloud;

    @Value
    @Builder
    public static class Realtime {
        String browserTransport;
        boolean websocketAvailable;
        boolean sseAvailable;
        boolean pollingAvailable;
    }

    @Value
    @Builder
    public static class History {
        boolean available;
        String backend;
        String reason;
    }

    @Value
    @Builder
    public static class Control {
        boolean available;
        boolean readbackSupported;
    }

    @Value
    @Builder
    public static class Shadow {
        boolean available;
    }

    @Value
    @Builder
    public static class Cloud {
        boolean monitoringAvailable;
        boolean managementAvailable;
    }
}
