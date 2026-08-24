package com.wangbin.collector.core.collector.protocol.fins.domain;

import lombok.Getter;

/**
 * 定义当前模块的业务组件。
 */
@Getter
public class FinsAddress {

    private final String rawAddress;
    private final String canonicalAddress;
    private final FinsMemoryArea memoryArea;
    private final int wordAddress;
    private final Integer bitOffset;
    private final String dataType;
    private final int elementCount;
    private final Integer stringLength;
    private final FinsByteOrder byteOrder;
    private final FinsWordOrder wordOrder;

    /**
     * 创建当前组件实例。
     */
    public FinsAddress(String rawAddress,
                       String canonicalAddress,
                       FinsMemoryArea memoryArea,
                       int wordAddress,
                       Integer bitOffset,
                       String dataType,
                       int elementCount,
                       Integer stringLength,
                       FinsByteOrder byteOrder,
                       FinsWordOrder wordOrder) {
        this.rawAddress = rawAddress;
        this.canonicalAddress = canonicalAddress;
        this.memoryArea = memoryArea;
        this.wordAddress = wordAddress;
        this.bitOffset = bitOffset;
        this.dataType = dataType;
        this.elementCount = Math.max(1, elementCount);
        this.stringLength = stringLength;
        this.byteOrder = byteOrder;
        this.wordOrder = wordOrder;
    }

    public boolean isBitUnit() {
        return bitOffset != null;
    }

    public boolean isStringType() {
        return "STRING".equals(dataType);
    }

    public boolean isArrayType() {
        return !isStringType() && elementCount > 1;
    }

    public boolean isScalar() {
        return !isArrayType();
    }

    /**
     * 执行当前业务逻辑。
     */
    public int scalarWordCount() {
        return switch (dataType) {
            case "BOOLEAN", "INT8", "UINT8", "INT16", "UINT16" -> 1;
            case "INT32", "UINT32", "FLOAT" -> 2;
            case "INT64", "UINT64", "DOUBLE" -> 4;
            case "STRING" -> requiredStringWordCount();
            default -> throw new IllegalStateException("Unsupported FINS dataType: " + dataType);
        };
    }

    /**
     * 查询并返回业务数据。
     */
    public int readUnitCount() {
        if (isBitUnit()) {
            return Math.max(1, elementCount);
        }
        if (isStringType()) {
            return requiredStringWordCount();
        }
        return scalarWordCount() * elementCount;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int responseByteLength() {
        return isBitUnit() ? readUnitCount() : readUnitCount() * 2;
    }

    /**
     * 校验业务条件和参数边界。
     */
    public int requiredStringWordCount() {
        if (stringLength == null || stringLength <= 0) {
            throw new IllegalStateException("STRING address requires stringLength");
        }
        return (stringLength + 1) / 2;
    }
}