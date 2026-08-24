package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.ControlCommandApplicationService;
import com.wangbin.collector.api.controller.dto.BatchPointWriteResponse;
import com.wangbin.collector.api.controller.dto.DeviceCommandRequest;
import com.wangbin.collector.api.controller.dto.DeviceCommandResponse;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.api.controller.dto.PointWriteResultResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点位写入和协议命令控制接口。
 *
 * <p>只负责控制台写入类接口路由，具体点位解析和写入编排由应用服务处理。</p>
 */
@RestController
@RequestMapping("/api/control")
@RequiredArgsConstructor
public class ControlController {

    private final ControlCommandApplicationService controlCommandApplicationService;

    /**
     * 写入单个点位。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointRef 点位标识、编码或上报字段
     * @param request 写入请求
     * @return 写入结果
     */
    @PostMapping("/device/{deviceId}/point/{pointRef}")
    public ApiResult<PointWriteResultResponse> writePoint(@PathVariable String deviceId,
                                                          @PathVariable String pointRef,
                                                          @Valid @RequestBody PointWriteRequest request) {
        return controlCommandApplicationService.writePoint(deviceId, pointRef, request);
    }

    /**
     * 批量写入点位。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 批量写入请求
     * @return 批量写入结果
     */
    @PostMapping("/device/{deviceId}/points")
    public ApiResult<BatchPointWriteResponse> writePoints(@PathVariable String deviceId,
                                                          @Valid @RequestBody PointWriteRequest request) {
        return controlCommandApplicationService.writePoints(deviceId, request);
    }

    /**
     * 执行协议命令。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 命令请求
     * @return 命令执行结果
     */
    @PostMapping("/device/{deviceId}/command")
    public ApiResult<DeviceCommandResponse> executeCommand(@PathVariable String deviceId,
                                                           @Valid @RequestBody DeviceCommandRequest request) {
        return controlCommandApplicationService.executeCommand(deviceId, request);
    }
}