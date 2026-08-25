package com.wangbin.collector.common.exception;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.web.result.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");

    @Test
    void businessExceptionShouldUseStandardApiResult() {
        ApiResult<?> result = handler.handleBusinessException(
                new BusinessException(1004, "操作失败"), request);

        assertEquals(1004, result.getCode());
        assertNull(result.getStatus());
        assertEquals("操作失败", result.getMessage());
    }

    @Test
    void collectorExceptionShouldPreserveExceptionContext() {
        ApiResult<?> result = handler.handleCollectorException(
                new CollectorException("采集失败", "dev-1", "point-1", DataQuality.TIMEOUT), request);

        assertEquals(500, result.getCode());
        assertEquals("采集失败", result.getMessage());
        assertEquals("dev-1", result.getExtra(CommonMapKeys.DEVICE_ID));
        assertEquals("point-1", result.getExtra(CommonMapKeys.POINT_ID));
        assertEquals(DataQuality.TIMEOUT.getCode(), result.getExtra("dataQuality"));
    }
}
