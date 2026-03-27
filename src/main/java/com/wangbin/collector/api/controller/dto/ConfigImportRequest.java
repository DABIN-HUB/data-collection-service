package com.wangbin.collector.api.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入请求
 */
@Data
public class ConfigImportRequest {

    /**
     * 导入目标集合
     */
    private List<ConfigBundle> bundles = new ArrayList<>();

    /**
     * 导入完成后是否立即触发设备配置刷新
     */
    private boolean reloadAfterImport;
}
