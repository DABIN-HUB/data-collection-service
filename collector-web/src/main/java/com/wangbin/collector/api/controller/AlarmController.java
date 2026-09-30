package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.AlarmLifecycleApplicationService;
import com.wangbin.collector.api.controller.dto.AlarmLifecycleResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 告警生命周期查询接口；确认仍由运维接口处理。 */
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {
    private final AlarmLifecycleApplicationService service;

    /** 查询触发告警并叠加恢复和确认状态。 */
    @GetMapping
    public ApiResult<AlarmLifecycleResponse> query(@RequestParam(required = false) String deviceId,
                                                   @RequestParam(required = false) String pointId,
                                                   @RequestParam(required = false) String pointCode,
                                                   @RequestParam(required = false) String ruleId,
                                                   @RequestParam(required = false) String level,
                                                   @RequestParam(required = false) String state,
                                                   @RequestParam(required = false) Long startTs,
                                                   @RequestParam(required = false) Long endTs,
                                                   @RequestParam(required = false) Integer limit) {
        return ApiResult.statusSuccess("告警查询成功", service.query(deviceId, pointId, pointCode, ruleId,
                level, state, startTs, endTs, limit));
    }
}
