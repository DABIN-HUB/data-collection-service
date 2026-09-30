import { requestApiData } from "./http";
import type { CloudFlushResponse, CloudOutboxDetail, CloudOutboxListItem, CloudTestResponse } from "@/types/cloud";

export function listCloudOutbox(params: { status?: string; deviceId?: string; limit?: number } = {}): Promise<CloudOutboxListItem[]> {
  return requestApiData<CloudOutboxListItem[]>({ url: "/api/cloud/outbox", method: "GET", params });
}
export function getCloudOutboxDetail(messageId: string): Promise<CloudOutboxDetail> {
  return requestApiData<CloudOutboxDetail>({ url: `/api/cloud/outbox/${encodeURIComponent(messageId)}`, method: "GET" });
}
export function replayCloudOutbox(messageId: string): Promise<unknown> {
  return requestApiData({ url: `/api/cloud/outbox/${encodeURIComponent(messageId)}/replay`, method: "POST" });
}
export function flushCloudOutbox(): Promise<CloudFlushResponse> {
  return requestApiData<CloudFlushResponse>({ url: "/api/cloud/flush", method: "POST" });
}
export function testCloudLink(): Promise<CloudTestResponse> {
  return requestApiData<CloudTestResponse>({ url: "/api/cloud/test", method: "POST" });
}
