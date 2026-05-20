package com.wangbin.collector.core.collector.protocol.snmp;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnmpCollectorTrapTest {

    @Test
    void shouldIngestSubscribedTrapOid() {
        TestSnmpCollector collector = new TestSnmpCollector();
        DataPoint point = point("p1", "1.3.6.1.4.1.100.1", "INTEGER");

        collector.doSubscribe(List.of(point));
        collector.handleTrapBindings(List.of(
                new VariableBinding(new OID(".1.3.6.1.4.1.100.1"), new Integer32(42))
        ), null);

        assertEquals("p1", collector.ingestedPointId);
        assertEquals(42, collector.ingestedValue);
    }

    private DataPoint point(String pointId, String oid, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointName(pointId);
        point.setAddress(oid);
        point.setDataType(dataType);
        point.setStatus(1);
        return point;
    }

    private static final class TestSnmpCollector extends SnmpCollector {
        private String ingestedPointId;
        private Object ingestedValue;

        @Override
        protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
            this.ingestedPointId = point.getPointId();
            this.ingestedValue = rawValue;
            return null;
        }
    }
}
