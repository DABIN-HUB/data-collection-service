package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.report.model.ReportData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网关级属性包聚合器。
 */
@Component
@RequiredArgsConstructor
public class CloudBatchAccumulator {

    private final CloudPackReportAssembler packReportAssembler;

    /**
     * 执行当前业务逻辑。
     */
    public Optional<CloudBatchReport> tryAssemble(CloudDeviceIdentity gatewayIdentity,
                                                  String rawGatewayDeviceId,
                                                  List<CloudAggregateSnapshot> snapshots,
                                                  CloudBatchFlushPolicy policy) {
        if (gatewayIdentity == null || !gatewayIdentity.valid()
                || snapshots == null || snapshots.size() < 2
                || policy == null || !policy.enabled()) {
            return Optional.empty();
        }

        List<CloudAggregateSnapshot> selected = new ArrayList<>();
        int propertyCount = 0;
        int estimatedBytes = 0;
        for (CloudAggregateSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.identity() == null || !snapshot.identity().valid()
                    || emptySnapshot(snapshot)) {
                continue;
            }
            int nextPropertyCount = propertyCount + snapshot.properties().size();
            if (!selected.isEmpty() && nextPropertyCount > policy.maxPropertiesPerPack()) {
                break;
            }
            int nextBytes = estimatedBytes + estimateBytes(snapshot);
            if (!selected.isEmpty() && nextBytes > policy.maxPayloadBytes()) {
                break;
            }
            selected.add(snapshot);
            propertyCount = nextPropertyCount;
            estimatedBytes = nextBytes;
            if (selected.size() >= policy.maxDevicesPerPack()) {
                break;
            }
        }

        if (selected.size() < 2) {
            return Optional.empty();
        }
        ReportData reportData = packReportAssembler.assemble(gatewayIdentity, rawGatewayDeviceId, selected);
        if (reportData == null) {
            return Optional.empty();
        }
        reportData.addMetadata("batchOptimized", true);
        reportData.addMetadata("batchDeviceCount", selected.size());
        reportData.addMetadata("batchPropertyCount", propertyCount);
        reportData.addMetadata("batchEstimatedBytes", estimatedBytes);
        return Optional.of(new CloudBatchReport(reportData, List.copyOf(selected)));
    }

    /**
     * 执行当前业务逻辑。
     */
    private int estimateBytes(CloudAggregateSnapshot snapshot) {
        int size = 128;
        size += estimate(snapshot.identity().productKey());
        size += estimate(snapshot.identity().deviceName());
        size += estimate(snapshot.properties());
        size += estimate(snapshot.events());
        size += estimate(snapshot.propertyQuality());
        size += estimate(snapshot.propertyTs());
        return size;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean emptySnapshot(CloudAggregateSnapshot snapshot) {
        return snapshot.properties().isEmpty() && snapshot.events().isEmpty();
    }

    /**
     * 执行当前业务逻辑。
     */
    private int estimate(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int size = 0;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            size += estimate(entry.getKey());
            size += estimate(entry.getValue());
        }
        return size;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int estimate(Object value) {
        return value == null ? 0 : value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record CloudBatchReport(ReportData reportData, List<CloudAggregateSnapshot> snapshots) {
    }
}
