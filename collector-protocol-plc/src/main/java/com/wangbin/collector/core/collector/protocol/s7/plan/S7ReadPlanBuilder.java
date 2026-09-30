package com.wangbin.collector.core.collector.protocol.s7.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.util.S7AddressParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 定义当前模块的业务组件。
 */
public class S7ReadPlanBuilder {

    private static final int DEFAULT_MAX_SEGMENT_SPAN_BYTES = 256;
    private static final Pattern DB_PATTERN = Pattern.compile("^%?DB(\\d+):(\\d+)(?:\\.(\\d+))?:(.+)$");
    private static final Pattern AREA_PATTERN = Pattern.compile("^%([IQM])(\\d+)(?:\\.(\\d+))?:(.+)$");

    /**
     * 创建并返回业务对象。
     */
    public List<S7ReadPlan> build(List<DataPoint> points, int maxFieldsPerRequest) {
        return build(points, maxFieldsPerRequest, null);
    }

    /** 单点地址/跨度错误隔离，不吞掉批量配置错误。 */
    public List<S7ReadPlan> buildIsolatingInvalidPoints(List<DataPoint> points,
                                                         int maxFieldsPerRequest,
                                                         Consumer<DataPoint> invalidPoint) {
        if (invalidPoint == null) {
            throw new IllegalArgumentException("invalidPoint handler is required");
        }
        return buildIsolatingInvalidPoints(points, maxFieldsPerRequest,
                (point, reason) -> invalidPoint.accept(point));
    }

    /** 单点异常原因回传给配置诊断，避免运行期无声丢点。 */
    public List<S7ReadPlan> buildIsolatingInvalidPoints(List<DataPoint> points,
                                                         int maxFieldsPerRequest,
                                                         BiConsumer<DataPoint, IllegalArgumentException> invalidPoint) {
        if (invalidPoint == null) {
            throw new IllegalArgumentException("invalidPoint handler is required");
        }
        return build(points, maxFieldsPerRequest, invalidPoint);
    }

    private List<S7ReadPlan> build(List<DataPoint> points,
                                   int maxFieldsPerRequest,
                                   BiConsumer<DataPoint, IllegalArgumentException> invalidPoint) {
        if (maxFieldsPerRequest <= 0) {
            throw new IllegalArgumentException("S7 maxFieldsPerRequest must be positive");
        }
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        int batchLimit = maxFieldsPerRequest;
        List<PlanCandidate> candidates = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                S7Address address = S7AddressParser.parse(point);
                candidates.add(toCandidate(point, address));
            } catch (IllegalArgumentException ex) {
                if (invalidPoint == null) {
                    throw ex;
                }
                invalidPoint.accept(point, ex);
            }
        }

        candidates.sort(Comparator
                .comparing(PlanCandidate::segmentKey)
                .thenComparingInt(PlanCandidate::startOffset)
                .thenComparingInt(PlanCandidate::bitOffset)
                .thenComparing(candidate -> candidate.point().getPointId() == null ? "" : candidate.point().getPointId()));

        List<S7ReadPlan> plans = new ArrayList<>();
        List<PlanCandidate> currentCandidates = new ArrayList<>();
        String currentSegmentKey = null;
        String currentArea = null;
        Integer currentDbNumber = null;
        boolean currentBlockOptimizable = false;
        int currentStartOffset = 0;
        int currentEndOffset = 0;

        for (PlanCandidate candidate : candidates) {
            boolean startNewPlan = currentCandidates.isEmpty()
                    || !candidate.segmentKey().equals(currentSegmentKey)
                    || candidate.blockOptimizable() != currentBlockOptimizable
                    || currentCandidates.size() >= batchLimit
                    || (candidate.blockOptimizable() && candidate.startOffset() > currentEndOffset)
                    || candidate.endOffsetExclusive() - currentStartOffset > DEFAULT_MAX_SEGMENT_SPAN_BYTES;
            if (startNewPlan) {
                if (!currentCandidates.isEmpty()) {
                    plans.add(buildPlan(currentSegmentKey, currentArea, currentDbNumber,
                            currentStartOffset, currentEndOffset, currentBlockOptimizable, currentCandidates));
                }
                currentCandidates = new ArrayList<>();
                currentSegmentKey = candidate.segmentKey();
                currentArea = candidate.area();
                currentDbNumber = candidate.dbNumber();
                currentBlockOptimizable = candidate.blockOptimizable();
                currentStartOffset = candidate.startOffset();
                currentEndOffset = candidate.endOffsetExclusive();
            } else {
                currentEndOffset = Math.max(currentEndOffset, candidate.endOffsetExclusive());
            }
            currentCandidates.add(candidate);
        }

        if (!currentCandidates.isEmpty()) {
            plans.add(buildPlan(currentSegmentKey, currentArea, currentDbNumber,
                    currentStartOffset, currentEndOffset, currentBlockOptimizable, currentCandidates));
        }
        return plans;
    }

    /**
     * 创建并返回业务对象。
     */
    private S7ReadPlan buildPlan(String segmentKey,
                                 String area,
                                 Integer dbNumber,
                                 int startOffset,
                                 int endOffsetExclusive,
                                 boolean blockOptimizable,
                                 List<PlanCandidate> candidates) {
        List<S7ReadPlanItem> items = new ArrayList<>(candidates.size());
        for (PlanCandidate candidate : candidates) {
            items.add(new S7ReadPlanItem(
                    candidate.point(),
                    candidate.address(),
                    candidate.startOffset(),
                    candidate.bitOffset(),
                    Math.max(1, candidate.endOffsetExclusive() - candidate.startOffset()),
                    candidate.blockOptimizable()
            ));
        }

        String blockReadAddress = blockOptimizable
                ? buildBlockReadAddress(area, dbNumber, startOffset, endOffsetExclusive - startOffset)
                : null;
        return new S7ReadPlan(segmentKey, area, dbNumber, startOffset, endOffsetExclusive,
                blockOptimizable, blockReadAddress, items);
    }

    /**
     * 解析或转换业务数据。
     */
    private PlanCandidate toCandidate(DataPoint point, S7Address address) {
        AddressLocation location = resolveLocation(address);
        int elementByteSize = estimateByteSize(address.getPlcType());
        long totalByteSize = (long) elementByteSize * address.getArraySize();
        long endOffsetExclusive = (long) location.byteOffset() + totalByteSize;
        if (totalByteSize <= 0 || endOffsetExclusive > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("S7 read span exceeds address range: " + address.getRawAddress());
        }
        boolean blockOptimizable = totalByteSize <= DEFAULT_MAX_SEGMENT_SPAN_BYTES
                && isBlockOptimizable(address, location);
        return new PlanCandidate(
                point,
                address,
                location.segmentKey(),
                location.area(),
                location.dbNumber(),
                location.byteOffset(),
                location.bitOffset(),
                (int) endOffsetExclusive,
                blockOptimizable
        );
    }

    private boolean isBlockOptimizable(S7Address address, AddressLocation location) {
        if (address == null || location == null || !location.byteAddressable()) {
            return false;
        }
        if (location.bitOffset() != 0) {
            return false;
        }
        String baseType = address.getBasePlcType();
        return !"BOOL".equals(baseType)
                && !baseType.startsWith("STRING")
                && !baseType.startsWith("WSTRING");
    }

    /**
     * 解析或转换业务数据。
     */
    private AddressLocation resolveLocation(S7Address address) {
        String plc4xAddress = address.getPlc4xAddress();
        Matcher dbMatcher = DB_PATTERN.matcher(plc4xAddress);
        if (dbMatcher.matches()) {
            Integer dbNumber = Integer.parseInt(dbMatcher.group(1));
            int byteOffset = Integer.parseInt(dbMatcher.group(2));
            int bitOffset = parseOptionalInt(dbMatcher.group(3));
            return new AddressLocation("DB:" + dbNumber, "DB", dbNumber, byteOffset, bitOffset, true);
        }

        Matcher areaMatcher = AREA_PATTERN.matcher(plc4xAddress);
        if (areaMatcher.matches()) {
            String areaCode = areaMatcher.group(1).toUpperCase(Locale.ROOT);
            int byteOffset = Integer.parseInt(areaMatcher.group(2));
            int bitOffset = parseOptionalInt(areaMatcher.group(3));
            String area = switch (areaCode) {
                case "I" -> "INPUT";
                case "Q" -> "OUTPUT";
                case "M" -> "MERKER";
                default -> address.getArea();
            };
            return new AddressLocation(area, area, null, byteOffset, bitOffset, true);
        }

        return new AddressLocation(address.getArea(), address.getArea(), null, 0, 0, false);
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildBlockReadAddress(String area, Integer dbNumber, int startOffset, int byteSpan) {
        if (byteSpan <= 0) {
            return null;
        }
        return switch (normalizeArea(area)) {
            case "DB" -> dbNumber != null ? "%DB" + dbNumber + ":" + startOffset + ":BYTE[" + byteSpan + "]" : null;
            case "INPUT" -> "%I" + startOffset + ":BYTE[" + byteSpan + "]";
            case "OUTPUT" -> "%Q" + startOffset + ":BYTE[" + byteSpan + "]";
            case "MERKER" -> "%M" + startOffset + ":BYTE[" + byteSpan + "]";
            default -> null;
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeArea(String area) {
        return area != null ? area.trim().toUpperCase(Locale.ROOT) : "";
    }

    /**
     * 执行当前业务逻辑。
     */
    private int estimateByteSize(String typeExpression) {
        if (typeExpression == null || typeExpression.isBlank()) {
            return 1;
        }
        String normalized = typeExpression.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("STRING(")) {
            return checkedStringByteSize(normalized, 1, 2);
        }
        if (normalized.startsWith("WSTRING(")) {
            return checkedStringByteSize(normalized, 2, 4);
        }
        return switch (normalized) {
            case "BOOL", "BYTE", "SINT", "USINT", "CHAR" -> 1;
            case "WORD", "INT", "UINT", "WCHAR", "DATE", "S5TIME" -> 2;
            case "DWORD", "DINT", "UDINT", "REAL", "TIME", "TIME_OF_DAY" -> 4;
            case "LWORD", "LINT", "ULINT", "LREAL", "LTIME", "DATE_AND_TIME" -> 8;
            default -> 1;
        };
    }

    /**
     * 字符串头部和字符宽度均计入读取跨度，避免整数溢出后误判为可批读。
     */
    private int checkedStringByteSize(String typeExpression, int charWidth, int headerSize) {
        long byteSize = (long) parseLength(typeExpression, 254) * charWidth + headerSize;
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("S7 string byte span exceeds address range: " + typeExpression);
        }
        return (int) byteSize;
    }

    /**
     * 解析或转换业务数据。
     */
    private int parseLength(String normalized, int defaultValue) {
        int start = normalized.indexOf('(');
        int end = normalized.indexOf(')');
        if (start < 0 || end <= start + 1) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(normalized.substring(start + 1, end)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int parseOptionalInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record PlanCandidate(DataPoint point,
                                 S7Address address,
                                 String segmentKey,
                                 String area,
                                 Integer dbNumber,
                                 int startOffset,
                                 int bitOffset,
                                 int endOffsetExclusive,
                                 boolean blockOptimizable) {
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record AddressLocation(String segmentKey,
                                   String area,
                                   Integer dbNumber,
                                   int byteOffset,
                                   int bitOffset,
                                   boolean byteAddressable) {
    }
}

