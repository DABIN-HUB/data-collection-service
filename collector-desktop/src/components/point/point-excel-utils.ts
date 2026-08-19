import * as XLSX from "xlsx";

import { normalizePointRows } from "./point-editor-utils";
import type { DataPoint } from "@/types/point";

const HEADER_TO_FIELD: Record<string, keyof DataPoint> = {
  "点位ID": "pointId",
  "点位编码": "pointCode",
  "点位名称": "pointName",
  "地址": "address",
  "数据类型": "dataType",
  "读写": "readWrite",
  "采集模式": "collectionMode",
  "单位": "unit",
  "报警启用": "alarmEnabled",
  "采集周期ms": "baseCollectionInterval",
  "缩放系数": "scalingFactor",
  "偏移量": "offset",
  "备注": "remark"
};

const FIELD_TO_HEADER = Object.fromEntries(Object.entries(HEADER_TO_FIELD).map(([header, field]) => [field, header])) as Record<string, string>;

export function serializePointWorkbook(points: DataPoint[]): ArrayBuffer {
  const rows = points.map((point) => Object.fromEntries(Object.entries(FIELD_TO_HEADER).map(([field, header]) => [header, point[field]])));
  const worksheet = XLSX.utils.json_to_sheet(rows);
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, worksheet, "点位配置");
  return XLSX.write(workbook, { type: "array", bookType: "xlsx" }) as ArrayBuffer;
}

export function parsePointWorkbook(content: ArrayBuffer): DataPoint[] {
  const workbook = XLSX.read(content, { type: "array" });
  const sheetName = workbook.SheetNames[0];
  if (!sheetName) {
    return [];
  }
  const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(workbook.Sheets[sheetName], { defval: "" });
  const points = rows.map((row) => {
    const point: DataPoint = {};
    for (const [header, field] of Object.entries(HEADER_TO_FIELD)) {
      const value = row[header];
      if (value !== "") {
        point[field] = normalizeImportedValue(field, value) as never;
      }
    }
    return point;
  });
  return normalizePointRows(points);
}

export function downloadPointWorkbook(points: DataPoint[], filename: string): void {
  const content = serializePointWorkbook(points);
  const blob = new Blob([content], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function normalizeImportedValue(field: keyof DataPoint, value: unknown): unknown {
  if (["alarmEnabled", "baseCollectionInterval", "scalingFactor", "offset"].includes(String(field))) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : undefined;
  }
  return String(value);
}
