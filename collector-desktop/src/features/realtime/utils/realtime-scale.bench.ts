import { bench, describe } from "vitest";

import { buildRealtimeSummary, normalizeAllDeviceRealtimeRows, normalizeRealtimeRows } from "./realtime-utils";
import {
  buildAllDeviceRealtimeScaleFixture,
  buildCompactAllDeviceRealtimeScaleFixture,
  buildSingleDeviceRealtimeScaleFixture,
  estimatePayloadSizeMetric,
  REALTIME_SCALE_CASES,
  removeDeviceNameForFallbackBenchmark,
  SINGLE_DEVICE_SCALE_CASES
} from "./realtime-scale-fixture";
import { extractCompactRealtimeRows } from "./realtime-compact-utils";
import { applyRealtimeDelta, buildRealtimeRowIdentityIndex } from "./realtime-delta";
import {
  buildRealtimeDeviceNameLookup,
  buildRealtimePageWindow,
  filterRealtimeRows,
  getPagedRealtimeRows,
  MAX_REALTIME_PAGE_SIZE
} from "./realtime-table-window";

const BENCH_OPTIONS = {
  iterations: 8,
  warmupIterations: 2,
  time: 100,
  warmupTime: 25
};
const MAX_DELTA_ROWS = 20_000;

const allDeviceCases = REALTIME_SCALE_CASES.map((scaleCase) => {
  const response = buildAllDeviceRealtimeScaleFixture(scaleCase);
  const compactResponse = buildCompactAllDeviceRealtimeScaleFixture(scaleCase);
  const json = JSON.stringify(response);
  const compactJson = JSON.stringify(compactResponse);
  const rows = normalizeAllDeviceRealtimeRows(response);
  const compactRows = extractCompactRealtimeRows(compactResponse);
  const rowsWithoutDeviceName = removeDeviceNameForFallbackBenchmark(rows);
  const deviceNameLookup = buildBenchmarkDeviceNameLookup(scaleCase.deviceCount);
  const size = estimatePayloadSizeMetric(scaleCase.label, scaleCase.totalPoints, scaleCase.deviceCount, response);
  const compactSize = estimatePayloadSizeMetric(scaleCase.label, scaleCase.totalPoints, scaleCase.deviceCount, compactResponse);
  const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: MAX_REALTIME_PAGE_SIZE });
  return {
    scaleCase,
    response,
    compactResponse,
    json,
    compactJson,
    rows,
    compactRows,
    rowsWithoutDeviceName,
    deviceNameLookup,
    size,
    compactSize,
    pageWindow
  };
});

const deltaSourceCase = allDeviceCases.find((testCase) => testCase.scaleCase.totalPoints === 100_000) || allDeviceCases[allDeviceCases.length - 1];
const deltaPayloadCases = [0, 1, 10, 20, 50, 100].map((percent) => buildDeltaPayloadCase(deltaSourceCase, percent));
const deltaApplyCases = [100, 1_000, 10_000].map((changedCount) => buildDeltaApplyCase(deltaSourceCase, changedCount));
const singleDeviceLookup = buildBenchmarkDeviceNameLookup(1);
const singleDeviceCases = SINGLE_DEVICE_SCALE_CASES.map((scaleCase) => {
  const response = buildSingleDeviceRealtimeScaleFixture(scaleCase);
  const json = JSON.stringify(response);
  const rows = normalizeRealtimeRows(response, "scale-device-001");
  const size = estimatePayloadSizeMetric(scaleCase.label, scaleCase.totalPoints, scaleCase.deviceCount, response);
  const pageWindow = buildRealtimePageWindow({ total: rows.length, page: 1, pageSize: MAX_REALTIME_PAGE_SIZE });
  return { scaleCase, response, json, rows, size, pageWindow };
});

describe("realtime scale payload", () => {
  for (const testCase of allDeviceCases) {
    bench(`all payload stringify ${testCase.scaleCase.label} rawBytes=${testCase.size.rawBytes}`, () => {
      JSON.stringify(testCase.response);
    }, BENCH_OPTIONS);

    bench(`all JSON parse ${testCase.scaleCase.label}`, () => {
      JSON.parse(testCase.json) as unknown;
    }, BENCH_OPTIONS);

    bench(`compact payload stringify ${testCase.scaleCase.label} rawBytes=${testCase.compactSize.rawBytes}`, () => {
      JSON.stringify(testCase.compactResponse);
    }, BENCH_OPTIONS);

    bench(`compact JSON parse ${testCase.scaleCase.label}`, () => {
      JSON.parse(testCase.compactJson) as unknown;
    }, BENCH_OPTIONS);
  }
});

describe("realtime change-aware delta payload", () => {
  for (const testCase of deltaPayloadCases) {
    if (testCase.mode === "delta") {
      bench(`delta payload stringify 100k changed=${testCase.changedCount} rawBytes=${testCase.rawBytes}`, () => {
        JSON.stringify(testCase.response);
      }, BENCH_OPTIONS);

      bench(`delta JSON parse 100k changed=${testCase.changedCount}`, () => {
        JSON.parse(testCase.json) as unknown;
      }, BENCH_OPTIONS);
      continue;
    }

    bench(`delta fallback decision 100k changed=${testCase.changedCount} mode=${testCase.mode}`, () => {
      if (testCase.mode !== "full") {
        throw new Error("增量过大场景必须回退全量同步");
      }
    }, BENCH_OPTIONS);
  }
});

describe("realtime change-aware delta apply", () => {
  for (const testCase of deltaApplyCases) {
    bench(`delta apply 100k changed=${testCase.changedCount}`, () => {
      applyRealtimeDelta([...testCase.rows], {
        cursor: { snapshotId: "snapshot-scale", configEpoch: 1, revision: 10 },
        successfulDeltaCycles: 0,
        rowIdentityIndex: testCase.index
      }, testCase.response, { deviceId: "" });
    }, BENCH_OPTIONS);
  }
});

describe("realtime scale all-device frontend CPU", () => {
  for (const testCase of allDeviceCases) {
    bench(`all normalize ${testCase.scaleCase.label} rows=${testCase.rows.length}`, () => {
      normalizeAllDeviceRealtimeRows(testCase.response);
    }, BENCH_OPTIONS);

    bench(`all summary ${testCase.scaleCase.label}`, () => {
      buildRealtimeSummary(testCase.rows);
    }, BENCH_OPTIONS);

    bench(`all filter no keyword ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rows, "", testCase.deviceNameLookup);
    }, BENCH_OPTIONS);

    bench(`all filter many matches ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rows, "规模点位", testCase.deviceNameLookup);
    }, BENCH_OPTIONS);

    bench(`all filter zero matches ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rows, "not-found-keyword", testCase.deviceNameLookup);
    }, BENCH_OPTIONS);

    bench(`all filter fallback device lookup ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rowsWithoutDeviceName, "not-found-keyword", testCase.deviceNameLookup);
    }, BENCH_OPTIONS);

    bench(`all render slice ${testCase.scaleCase.label} pageSize=${MAX_REALTIME_PAGE_SIZE}`, () => {
      getPagedRealtimeRows(testCase.rows, testCase.pageWindow);
    }, BENCH_OPTIONS);

    bench(`compact row extraction ${testCase.scaleCase.label} rows=${testCase.compactRows.length}`, () => {
      extractCompactRealtimeRows(testCase.compactResponse);
    }, BENCH_OPTIONS);
  }
});

describe("realtime scale single-device frontend CPU", () => {
  for (const testCase of singleDeviceCases) {
    bench(`single normalize ${testCase.scaleCase.label} rows=${testCase.rows.length} rawBytes=${testCase.size.rawBytes}`, () => {
      normalizeRealtimeRows(testCase.response, "scale-device-001");
    }, BENCH_OPTIONS);

    bench(`single summary ${testCase.scaleCase.label}`, () => {
      buildRealtimeSummary(testCase.rows);
    }, BENCH_OPTIONS);

    bench(`single filter many matches ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rows, "规模点位", singleDeviceLookup);
    }, BENCH_OPTIONS);

    bench(`single filter zero matches ${testCase.scaleCase.label}`, () => {
      filterRealtimeRows(testCase.rows, "not-found-keyword", singleDeviceLookup);
    }, BENCH_OPTIONS);

    bench(`single render slice ${testCase.scaleCase.label} pageSize=${MAX_REALTIME_PAGE_SIZE}`, () => {
      const pageWindow = buildRealtimePageWindow({ total: testCase.rows.length, page: 1, pageSize: MAX_REALTIME_PAGE_SIZE });
      getPagedRealtimeRows(testCase.rows, pageWindow);
    }, BENCH_OPTIONS);
  }
});

function buildBenchmarkDeviceNameLookup(deviceCount: number): Map<string, string> {
  const devices = Array.from({ length: deviceCount }, (_, index) => ({
    normalizedId: `scale-device-${String(index + 1).padStart(3, "0")}`,
    displayName: `规模测试设备${String(index + 1).padStart(3, "0")}`
  }));
  return buildRealtimeDeviceNameLookup(devices);
}

function buildDeltaPayloadCase(testCase: typeof allDeviceCases[number], percent: number) {
  const changedCount = Math.floor(testCase.scaleCase.totalPoints * percent / 100);
  const mode = changedCount > MAX_DELTA_ROWS ? "full" : "delta";
  const rows = testCase.compactRows.slice(0, changedCount).map((row, index) => ({
    ...row,
    value: typeof row.value === "number" ? row.value + 1 : row.value,
    lastUpdateTime: 1_900_000_000_000 + index
  }));
  const response = {
    status: "success",
    scope: "all",
    resetRequired: false,
    snapshotId: "snapshot-scale",
    configEpoch: 1,
    fromRevision: 10,
    revision: 11,
    changedCount: rows.length,
    rows,
    timestamp: 1_900_000_000_000
  };
  const json = JSON.stringify(response);
  return {
    percent,
    changedCount,
    mode,
    response,
    json,
    rawBytes: json.length,
    fullCompactBytes: testCase.compactSize.rawBytes,
    percentOfFull: json.length / testCase.compactSize.rawBytes * 100
  };
}

function buildDeltaApplyCase(testCase: typeof allDeviceCases[number], changedCount: number) {
  const rows = [...testCase.compactRows];
  const index = buildRealtimeRowIdentityIndex(rows);
  const changedRows = rows.slice(0, changedCount).map((row, rowIndex) => ({
    ...row,
    value: typeof row.value === "number" ? row.value + 1 : row.value,
    lastUpdateTime: 1_900_000_000_000 + rowIndex
  }));
  return {
    rows,
    index,
    changedCount,
    response: {
      status: "success",
      scope: "all",
      resetRequired: false,
      snapshotId: "snapshot-scale",
      configEpoch: 1,
      fromRevision: 10,
      revision: 11,
      changedCount,
      rows: changedRows,
      timestamp: 1_900_000_000_000
    }
  };
}
