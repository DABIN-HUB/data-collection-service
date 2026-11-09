package com.wangbin.collector.config.sqlite.migration;

import java.util.List;

/** wangbin 旧文件预检摘要，只包含计数和受控错误，不携带配置负载。 */
public record MigrationInspection(boolean sourceExists, String sourceSha256, int deviceCount,
                                  int connectionCount, int pointCount, int missingDeviceIds,
                                  int duplicateDeviceIds, int missingPointIds, int duplicatePointIds,
                                  List<String> problems) {
    public MigrationInspection {
        problems = List.copyOf(problems);
    }

    public boolean canMigrate() {
        return sourceExists && problems.isEmpty();
    }
}
