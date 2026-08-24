package com.wangbin.collector.storage.service;

import com.taosdata.jdbc.TaosPrepareStatement;
import com.taosdata.jdbc.ws.TSWSPreparedStatement;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketStmtTdengineTelemetryWriterTest {

    @Test
    void currentDriverMustExposeRealWebSocketStmtApi() {
        assertThat(TaosPrepareStatement.class.isAssignableFrom(TSWSPreparedStatement.class)).isTrue();
    }

    @Test
    void wsStmtWriterMustPreserveV2Semantics() throws Exception {
        FakeDriver driver = new FakeDriver();
        DriverManager.registerDriver(driver);
        try {
            WebSocketStmtTdengineTelemetryWriter writer = new WebSocketStmtTdengineTelemetryWriter(properties());

            TdengineWriteOutcome outcome = writer.writeBatch(
                    new TdengineWriteTarget("wangbin_collector", "d_dev_1_v2"),
                    List.of(row(1000L, "point-A", "1"), row(1000L, "point-B", "2")));

            assertThat(outcome.rows()).isEqualTo(2);
            assertThat(outcome.tables()).isEqualTo(1);
            assertThat(driver.calls.sql).startsWith("INSERT INTO wangbin_collector.telemetry_super_v2");
            assertThat(driver.calls.columnDataAddBatch).isEqualTo(1);
            assertThat(driver.calls.columnDataExecuteBatch).isEqualTo(1);
            assertThat(driver.calls.boundValues.get("setString:1"))
                    .containsExactly("d_dev_1_v2", "d_dev_1_v2");
            assertThat(driver.calls.boundValues.get("setTimestamp:2")).containsExactly(1000L, 1000L);
            assertThat(driver.calls.boundValues.get("setString:3")).containsExactly("point-A", "point-B");
            assertThat(driver.calls.boundValues.get("setLong:4")).containsExactly(1000L, 1000L);
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void wsStmtBatchMustWriteAllRows() throws Exception {
        FakeDriver driver = new FakeDriver();
        DriverManager.registerDriver(driver);
        try {
            WebSocketStmtTdengineTelemetryWriter writer = new WebSocketStmtTdengineTelemetryWriter(properties());

            writer.writeBatch(
                    new TdengineWriteTarget("wangbin_collector", "d_dev_2_v2"),
                    List.of(row(1000L, "p1", "1"), row(1001L, "p2", "2"), row(1002L, "p3", "3")));

            assertThat(driver.calls.boundValues.get("setString:3")).hasSize(3);
            assertThat(driver.calls.boundValues.get("setNString:8")).containsExactly("1", "2", "3");
            assertThat(driver.calls.boundValues.get("setBoolean:11")).containsExactly(true, true, true);
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private TdengineProperties properties() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("wangbin_collector");
        properties.getWrite().setWebsocketUrl(FakeDriver.URL);
        properties.getWrite().setWebsocketUsername("");
        properties.getWrite().setWebsocketPassword("");
        return properties;
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

    private static final class FakeDriver implements Driver {

        private static final String URL = "jdbc:TDENGINE-WS-STMT-FAKE://unit";

        private final StatementCalls calls = new StatementCalls();

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            return connection(calls);
        }

        @Override
        public boolean acceptsURL(String url) {
            return URL.equals(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private static Connection connection(StatementCalls calls) {
        return (Connection) Proxy.newProxyInstance(
                WebSocketStmtTdengineTelemetryWriterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        calls.sql = String.valueOf(args[0]);
                        return statement(calls);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement statement(StatementCalls calls) {
        return (PreparedStatement) Proxy.newProxyInstance(
                WebSocketStmtTdengineTelemetryWriterTest.class.getClassLoader(),
                new Class<?>[]{TaosPrepareStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length >= 2 && args[1] instanceof List<?> values) {
                        calls.boundValues.put(name + ":" + args[0], List.copyOf(values.stream()
                                .map(value -> (Object) value)
                                .toList()));
                        return null;
                    }
                    if ("columnDataAddBatch".equals(name)) {
                        calls.columnDataAddBatch++;
                        return defaultValue(method.getReturnType());
                    }
                    if ("columnDataExecuteBatch".equals(name)) {
                        calls.columnDataExecuteBatch++;
                        return defaultValue(method.getReturnType());
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Character.TYPE) {
            return (char) 0;
        }
        return null;
    }

    private static final class StatementCalls {

        private String sql;
        private int columnDataAddBatch;
        private int columnDataExecuteBatch;
        private final Map<String, List<Object>> boundValues = new LinkedHashMap<>();
    }
}
