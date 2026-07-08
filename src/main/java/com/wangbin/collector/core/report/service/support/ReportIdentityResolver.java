package com.wangbin.collector.core.report.service.support;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.model.ReportIdentity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 解析点位上报身份。云平台物模型映射统一由 cloudBindings 处理。
 */
@Component
public class ReportIdentityResolver {

    public ProcessResult toProcessResult(Object cacheValue) {
        if (cacheValue instanceof ProcessResult processResult) {
            return processResult;
        }
        ProcessResult result = new ProcessResult();
        result.setSuccess(true);
        result.setRawValue(cacheValue);
        result.setProcessedValue(cacheValue);
        result.setQuality(QualityEnum.GOOD.getCode());
        return result;
    }

    public List<ReportIdentity> resolve(String gatewayDeviceId,
                                         DataPoint point,
                                         String defaultProductKey) {
        return resolveLegacyIdentities(gatewayDeviceId, point, defaultProductKey);
    }

    private List<ReportIdentity> resolveLegacyIdentities(String gatewayDeviceId,
                                                         DataPoint point,
                                                         String defaultProductKey) {
        LinkedHashSet<String> deviceNames = toOrderedSet(point != null ? point.getAdditionalConfig("reportDeviceName") : null);
        if (deviceNames.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> productKeys = toOrderedSet(point != null
                ? Optional.ofNullable(point.getAdditionalConfig("productKey"))
                .orElse(point.getAdditionalConfig("reportProductKey"))
                : null);
        List<ReportIdentity> result = new ArrayList<>(deviceNames.size());
        List<String> pkList = new ArrayList<>(productKeys);
        boolean sameSize = !pkList.isEmpty() && pkList.size() == deviceNames.size();
        String fallbackPk = pkList.isEmpty() ? defaultProductKey : pkList.get(0);
        int index = 0;
        for (String name : deviceNames) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            String pk = sameSize ? pkList.get(index) : fallbackPk;
            if (pk == null || pk.isEmpty()) {
                pk = defaultProductKey;
            }
            result.add(new ReportIdentity(gatewayDeviceId, name, pk));
            index++;
        }
        return result;
    }

    private LinkedHashSet<String> toOrderedSet(Object configured) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (configured == null) {
            return result;
        }
        if (configured instanceof Collection<?> collection) {
            for (Object item : collection) {
                addString(result, item);
            }
        } else if (configured.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(configured);
            for (int i = 0; i < length; i++) {
                addString(result, java.lang.reflect.Array.get(configured, i));
            }
        } else {
            addString(result, configured);
        }
        return result;
    }

    private void addString(Set<String> bucket, Object raw) {
        String normalized = normalizeString(raw);
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        if (normalized.contains(",")) {
            for (String part : normalized.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    bucket.add(trimmed);
                }
            }
            return;
        }
        bucket.add(normalized);
    }

    private String normalizeString(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }
}