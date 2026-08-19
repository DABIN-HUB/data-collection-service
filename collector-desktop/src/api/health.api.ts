import { request } from "./http";

export function getHealth(): Promise<unknown> {
  return request<unknown>({ url: "/health", method: "GET" });
}
