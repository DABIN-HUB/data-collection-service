import { request } from "./http";
import type { ProtocolFieldConfig, ProtocolSchema } from "@/types/protocol";

export function listProtocols(): Promise<ProtocolSchema[]> {
  return request<ProtocolSchema[]>({ url: "/api/protocols", method: "GET" });
}

export function getProtocol(protocol: string): Promise<ProtocolSchema> {
  return request<ProtocolSchema>({ url: `/api/protocols/${encodeURIComponent(protocol)}`, method: "GET" });
}

export function getProtocolFields(protocol: string): Promise<ProtocolFieldConfig[]> {
  return request<ProtocolFieldConfig[]>({ url: `/api/protocols/${encodeURIComponent(protocol)}/fields`, method: "GET" });
}
