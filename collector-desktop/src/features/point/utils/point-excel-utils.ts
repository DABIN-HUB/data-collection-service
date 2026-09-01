import { normalizePointRows } from "@/features/point/utils/point-editor-utils";
import type { DataPoint } from "@/types/point";

export const MAX_POINT_IMPORT_SIZE_BYTES = 1024 * 1024;
export const MAX_POINT_IMPORT_ROWS = 2000;

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
const CSV_HEADERS = Object.keys(HEADER_TO_FIELD);

export interface PointImportParseOptions {
  maxRows?: number;
}

export interface PointImportFileLike {
  name: string;
  size: number;
}

export function serializePointCsv(points: DataPoint[]): string {
  const rows = points.map((point) => CSV_HEADERS.map((header) => csvCell(point[HEADER_TO_FIELD[header]])));
  return [CSV_HEADERS.map(csvCell).join(","), ...rows.map((row) => row.join(","))].join("\r\n");
}

export function parsePointCsv(content: string, options: PointImportParseOptions = {}): DataPoint[] {
  const rows = parseCsvRows(content.replace(/^\uFEFF/, ""));
  if (rows.length === 0) {
    return [];
  }
  const maxRows = options.maxRows ?? MAX_POINT_IMPORT_ROWS;
  const [headers, ...dataRows] = rows;
  const effectiveRows = dataRows.filter((row) => row.some((cell) => cell.trim() !== ""));
  if (effectiveRows.length > maxRows) {
    throw new Error(`最多允许导入 ${maxRows} 行点位`);
  }
  const points = effectiveRows.map((row) => {
    const point: DataPoint = {};
    headers.forEach((header, index) => {
      const field = HEADER_TO_FIELD[header.trim()];
      const value = row[index] ?? "";
      if (field && value !== "") {
        point[field] = normalizeImportedValue(field, value) as never;
      }
    });
    return point;
  });
  return normalizePointRows(points);
}

export function validatePointImportFile(file: PointImportFileLike): void {
  const name = String(file.name || "").toLowerCase();
  if (!name.endsWith(".csv")) {
    throw new Error("仅支持 CSV 点位文件，请先另存为 .csv 后导入");
  }
  if (file.size > MAX_POINT_IMPORT_SIZE_BYTES) {
    throw new Error(`点位导入文件过大，最大允许 ${Math.floor(MAX_POINT_IMPORT_SIZE_BYTES / 1024)} KB`);
  }
}

export function downloadPointCsv(points: DataPoint[], filename: string): void {
  const content = `\uFEFF${serializePointCsv(points)}`;
  const blob = new Blob([content], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function parseCsvRows(content: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];
    const next = content[index + 1];
    if (quoted) {
      if (char === '"' && next === '"') {
        cell += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"') {
      quoted = true;
      continue;
    }
    if (char === ",") {
      row.push(cell);
      cell = "";
      continue;
    }
    if (char === "\n") {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = "";
      continue;
    }
    if (char !== "\r") {
      cell += char;
    }
  }
  row.push(cell);
  if (row.length > 1 || row[0] !== "") {
    rows.push(row);
  }
  return rows;
}

function csvCell(value: unknown): string {
  const text = value === undefined || value === null ? "" : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function normalizeImportedValue(field: keyof DataPoint, value: unknown): unknown {
  if (["alarmEnabled", "baseCollectionInterval", "scalingFactor", "offset"].includes(String(field))) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : undefined;
  }
  return String(value);
}
