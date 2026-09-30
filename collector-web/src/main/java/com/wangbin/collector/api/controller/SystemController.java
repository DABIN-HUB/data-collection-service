package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.SystemCapabilitiesApplicationService;
import com.wangbin.collector.api.controller.dto.SystemCapabilitiesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统实际能力声明接口。 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {
    private final SystemCapabilitiesApplicationService capabilitiesService;

    @GetMapping("/capabilities")
    public SystemCapabilitiesResponse getCapabilities() {
        return capabilitiesService.getCapabilities();
    }
}
