package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import org.apache.plc4x.java.api.value.PlcValue;

public interface CollectorValueCodec {

    Object read(PlcValue plcValue);

    Object write(Object value);
}