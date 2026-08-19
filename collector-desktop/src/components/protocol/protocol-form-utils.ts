import type { ProtocolFieldConfig } from "@/types/protocol";

export interface ProtocolFieldGroup {
  name: string;
  fields: ProtocolFieldConfig[];
}

export type ProtocolFormModel = Record<string, string | number | boolean | null>;

export type ConnectionPayload = Record<string, unknown>;

export function buildProtocolInitialModel(fields: ProtocolFieldConfig[]): ProtocolFormModel {
  return Object.fromEntries(fields.map((field) => [field.name, normalizeDefaultValue(field)]));
}

export function groupProtocolFields(fields: ProtocolFieldConfig[]): ProtocolFieldGroup[] {
  const groups = new Map<string, ProtocolFieldConfig[]>();
  for (const field of fields) {
    const groupName = field.group || "基础参数";
    const groupFields = groups.get(groupName) ?? [];
    groupFields.push(field);
    groups.set(groupName, groupFields);
  }
  return Array.from(groups.entries()).map(([name, groupFields]) => ({ name, fields: groupFields }));
}

export function validateProtocolModel(fields: ProtocolFieldConfig[], model: ProtocolFormModel): string[] {
  const errors: string[] = [];
  for (const field of fields) {
    const value = model[field.name];
    const label = field.label || field.name;
    if (field.required && isBlank(value)) {
      errors.push(`${label}不能为空`);
      continue;
    }
    if (!isBlank(value) && (field.type === "number" || field.type === "integer") && !Number.isFinite(Number(value))) {
      errors.push(`${label}必须是数字`);
    }
    if (!isBlank(value) && field.options && field.options.length > 0 && !field.options.includes(String(value))) {
      errors.push(`${label}必须是允许的选项`);
    }
  }
  return errors;
}

export function extractProtocolModel(fields: ProtocolFieldConfig[], connection: ConnectionPayload | null | undefined): ProtocolFormModel {
  const source = connection || {};
  return Object.fromEntries(fields.map((field) => {
    const value = readFieldValue(source, field);
    return [field.name, value === undefined ? normalizeDefaultValue(field) : normalizeFormValue(field, value)];
  }));
}

export function buildConnectionPayload(fields: ProtocolFieldConfig[], model: ProtocolFormModel, base: ConnectionPayload = {}): ConnectionPayload {
  const payload = clonePlainObject(base);
  if (!isPlainObject(payload.extJson)) {
    payload.extJson = {};
  }
  for (const field of fields) {
    const value = model[field.name];
    if (value === undefined) {
      continue;
    }
    writeFieldValue(payload, field, value);
  }
  return payload;
}

export function getPathValue(source: unknown, path: string): unknown {
  if (!source || !path) {
    return undefined;
  }
  const segments = path.split(".").filter(Boolean);
  let current: unknown = source;
  for (const segment of segments) {
    if (!isPlainObject(current)) {
      return undefined;
    }
    current = current[segment];
  }
  return current;
}

export function setPathValue(target: Record<string, unknown>, path: string, value: unknown): void {
  const segments = path.split(".").filter(Boolean);
  if (segments.length === 0) {
    return;
  }
  let current: Record<string, unknown> = target;
  for (const segment of segments.slice(0, -1)) {
    const next = current[segment];
    if (!isPlainObject(next)) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  }
  current[segments[segments.length - 1]] = value;
}

function normalizeDefaultValue(field: ProtocolFieldConfig): string | number | boolean | null {
  const rawValue = field.defaultValue;
  if (field.type === "boolean") {
    return rawValue === "true" || rawValue === "1";
  }
  if (field.type === "number") {
    if (rawValue === undefined || rawValue === null || rawValue === "") {
      return null;
    }
    const numberValue = Number(rawValue);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  return rawValue ?? "";
}

function readFieldValue(connection: ConnectionPayload, field: ProtocolFieldConfig): unknown {
  if (field.storage === "extJson") {
    return getPathValue(connection.extJson, field.name);
  }
  return getPathValue(connection, field.name);
}

function writeFieldValue(payload: ConnectionPayload, field: ProtocolFieldConfig, value: unknown): void {
  if (field.storage === "extJson") {
    const extJson = isPlainObject(payload.extJson) ? payload.extJson : {};
    payload.extJson = extJson;
    setPathValue(extJson, field.name, value);
    return;
  }
  setPathValue(payload, field.name, value);
}

function normalizeFormValue(field: ProtocolFieldConfig, value: unknown): string | number | boolean | null {
  if (value === null) {
    return null;
  }
  if (field.type === "boolean") {
    return value === true || value === "true" || value === "1" || value === 1;
  }
  if (field.type === "number" || field.type === "integer") {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return value;
  }
  return typeof value === "string" ? value : String(value);
}

function clonePlainObject(source: ConnectionPayload): ConnectionPayload {
  return JSON.parse(JSON.stringify(source || {})) as ConnectionPayload;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || String(value).trim() === "";
}
