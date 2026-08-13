package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectJdbcTdengineTelemetryWriterTest {

    @Test
    void directRestWriterMustPreserveV2Semantics() {
        String sql = DirectJdbcTdengineTelemetryWriter.buildSingleTableInsert(
                new TdengineWriteTarget("wangbin_collector", "d_dev_1_v2"),
                List.of(row(1000L, "point-A", "O'Brien"), row(1000L, "point-B", null)));

        assertThat(sql).startsWith("INSERT INTO wangbin_collector.d_dev_1_v2");
        assertThat(sql).contains("(ts,point_key,event_ts,point_id,point_code,point_name,value_text,unit");
        assertThat(sql).contains("(1000,'point-A',1000");
        assertThat(sql).contains("(1000,'point-B',1000");
        assertThat(sql).contains("'O''Brien'");
        assertThat(sql).contains("NULL");
    }

    @Test
    void sameTimestampDifferentPointKeyMustCoexist() {
        String sql = DirectJdbcTdengineTelemetryWriter.buildSingleTableInsert(
                new TdengineWriteTarget("wangbin_collector", "d_same_ts_v2"),
                List.of(row(2000L, "point-A", "A"), row(2000L, "point-B", "B")));

        assertThat(sql).contains("'point-A'");
        assertThat(sql).contains("'point-B'");
        assertThat(sql).contains("(2000,'point-A',2000");
        assertThat(sql).contains("(2000,'point-B',2000");
    }

    @Test
    void directRestFailureMustPropagateToExistingFallback() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("tdengine down"));
        DirectJdbcTdengineTelemetryWriter writer = new DirectJdbcTdengineTelemetryWriter(dataSource);

        assertThatThrownBy(() -> writer.writeBatch(
                new TdengineWriteTarget("wangbin_collector", "d_fail_v2"), List.of(row(1L, "p1", "1"))))
                .isInstanceOf(TdengineWriteException.class)
                .hasMessageContaining("Direct REST");
    }

    private TelemetryInsertRow row(long eventTs, String pointKey, String value) {
        return new TelemetryInsertRow(
                eventTs,
                pointKey,
                pointKey,
                pointKey,
                "点位" + pointKey,
                value,
                "C",
                100,
                true,
                "ok",
                "{}",
                "{}",
                "{}");
    }
}
