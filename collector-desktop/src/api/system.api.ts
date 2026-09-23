import { requestRaw } from "./http";
import type { SystemCapabilities } from "@/types/system";

export function getSystemCapabilities(): Promise<SystemCapabilities> {
  return requestRaw<SystemCapabilities>({ url: "/api/system/capabilities", method: "GET" });
}
