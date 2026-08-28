export const RouteNames = {
  LOGIN: "login",
  DASHBOARD: "dashboard",
  REALTIME: "realtime",
  HISTORY: "history",
  ALARM: "alarm",
  DEVICE: "device",
  DEVICE_WORKBENCH: "device-workbench",
  COLLECTION: "collect",
  CLOUD: "cloud",
  DIAGNOSTIC: "diagnostic",
  LOG: "log",
  NETWORK: "network",
  CONTROL: "control",
  SHADOW: "shadow"
} as const;

export type RouteName = typeof RouteNames[keyof typeof RouteNames];

export type LegacyModuleKey = "overview" | "realtime" | "history" | "alarm" | "device" | "collect" | "cloud" | "diag" | "log" | "network" | "workbench";

export type WorkbenchRouteTab = "control" | "shadow";

const legacyModuleByRoutePath: Record<string, LegacyModuleKey | WorkbenchRouteTab> = {
  dashboard: "overview",
  realtime: "realtime",
  history: "history",
  alarm: "alarm",
  device: "device",
  "device/workbench": "workbench",
  collect: "collect",
  cloud: "cloud",
  diagnostic: "diag",
  log: "log",
  network: "network",
  control: "control",
  shadow: "shadow"
};

const routePathByLegacyModule: Record<LegacyModuleKey, string> = {
  overview: "/dashboard",
  realtime: "/realtime",
  history: "/history",
  alarm: "/alarm",
  device: "/device",
  collect: "/collect",
  cloud: "/cloud",
  diag: "/diagnostic",
  log: "/log",
  network: "/network",
  workbench: "/device/workbench"
};

export function normalizeRoutePath(path: string): string {
  return path.replace(/^\//, "").replace(/\/$/, "") || "dashboard";
}

export function resolveLegacyModuleByRoutePath(path: string): LegacyModuleKey | WorkbenchRouteTab {
  return legacyModuleByRoutePath[normalizeRoutePath(path)] || "overview";
}

export function routePathForLegacyModule(module: LegacyModuleKey): string {
  return routePathByLegacyModule[module] || "/dashboard";
}
