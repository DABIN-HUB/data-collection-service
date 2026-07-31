package com.wangbin.collector.core.collector.protocol.iec101.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * IEC60870-5-101 常用类型标识。
 */
public enum Iec101Type {

    M_SP_NA_1(1),
    M_SP_TA_1(2),
    M_DP_NA_1(3),
    M_DP_TA_1(4),
    M_ST_NA_1(5),
    M_ST_TA_1(6),
    M_BO_NA_1(7),
    M_BO_TA_1(8),
    M_ME_NA_1(9),
    M_ME_TA_1(10),
    M_ME_NB_1(11),
    M_ME_TB_1(12),
    M_ME_NC_1(13),
    M_ME_TC_1(14),
    M_IT_NA_1(15),
    M_IT_TA_1(16),
    M_PS_NA_1(20),
    M_ME_ND_1(21),
    M_SP_TB_1(30),
    M_DP_TB_1(31),
    M_ST_TB_1(32),
    M_BO_TB_1(33),
    M_ME_TD_1(34),
    M_ME_TE_1(35),
    M_ME_TF_1(36),
    M_IT_TB_1(37),
    C_SC_NA_1(45),
    C_DC_NA_1(46),
    C_RC_NA_1(47),
    C_SE_NA_1(48),
    C_SE_NB_1(49),
    C_SE_NC_1(50),
    C_BO_NA_1(51),
    C_SC_TA_1(58),
    C_DC_TA_1(59),
    C_RC_TA_1(60),
    C_SE_TA_1(61),
    C_SE_TB_1(62),
    C_SE_TC_1(63),
    C_BO_TA_1(64),
    C_IC_NA_1(100),
    C_CI_NA_1(101),
    C_RD_NA_1(102),
    C_CS_NA_1(103);

    private final int typeId;

    /**
     * 创建当前组件实例。
     */
    Iec101Type(int typeId) {
        this.typeId = typeId;
    }

    /**
     * 执行当前业务逻辑。
     */
    public int typeId() {
        return typeId;
    }

    /**
     * 解析或转换业务数据。
     */
    public static Iec101Type parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IEC101 类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d+")) {
            return fromTypeId(Integer.parseInt(normalized));
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 IEC101 类型: " + value));
    }

    /**
     * 创建并返回业务对象。
     */
    public static Iec101Type fromTypeId(int typeId) {
        return Arrays.stream(values())
                .filter(type -> type.typeId == typeId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 IEC101 TypeId: " + typeId));
    }
}
