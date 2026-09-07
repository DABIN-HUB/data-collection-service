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
