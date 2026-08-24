package com.wangbin.collector.storage.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TdengineWriteMetricRecorderTest {

    @Test
    void latencyMetricsMustUseMeasurementWindowSampling() {
        TdengineWriteMetricRecorder recorder = new TdengineWriteMetricRecorder(3);

        for (int index = 1; index <= 5; index++) {
            recorder.recordSuccess(TdengineWriteOutcome.success(
                    index, 1, false, index, index * 10L, index * 100L, index * 1000L));
        }

        TdengineWriteMetrics metrics = recorder.snapshot();
        assertThat(metrics.writeRequests()).isEqualTo(5);
        assertThat(metrics.writtenRows()).isEqualTo(15);
        assertThat(metrics.sampleCount()).isEqualTo(3);
        assertThat(metrics.totalRecordedSamples()).isEqualTo(5);
        assertThat(metrics.overwrittenSamples()).isEqualTo(2);
        assertThat(metrics.rowsPerRequestMax()).isGreaterThanOrEqualTo(3);
        assertThat(metrics.totalWriteP95Ms()).isGreaterThan(0D);
    }

    @Test
    void writerFailureMustNotCauseSilentLoss() {
        TdengineWriteMetricRecorder recorder = new TdengineWriteMetricRecorder(4);

        recorder.recordFailure(TdengineWriteOutcome.success(50, 1, false, 0L, 0L, 1_000_000L, 1_000_000L));

        TdengineWriteMetrics metrics = recorder.snapshot();
        assertThat(metrics.writeRequests()).isZero();
        assertThat(metrics.writeFailures()).isEqualTo(1L);
        assertThat(metrics.sampleCount()).isEqualTo(1);
    }

    @Test
    void resetMustStartNewMeasurementWindow() {
        TdengineWriteMetricRecorder recorder = new TdengineWriteMetricRecorder(4);
        recorder.recordSuccess(TdengineWriteOutcome.success(50, 1, false, 0L, 0L, 1_000L, 1_000L));

        recorder.reset();

        TdengineWriteMetrics metrics = recorder.snapshot();
        assertThat(metrics.writeRequests()).isZero();
        assertThat(metrics.writtenRows()).isZero();
        assertThat(metrics.sampleCount()).isZero();
    }
}
