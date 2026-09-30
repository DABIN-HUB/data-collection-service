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

export type WorkbenchRouteTab = "control" | "shadow";
export type WorkbenchNavigationTab = "config" | WorkbenchRouteTab;

const workbenchTabByRoutePath: Record<string, WorkbenchRouteTab> = {
  control: "control",
  shadow: "shadow"
};

const routePathByWorkbenchTab: Record<WorkbenchNavigationTab, string> = {
  config: "/device/workbench",
  control: "/control",
  shadow: "/shadow"
};

export function normalizeRoutePath(path: string): string {
  return path.replace(/^\//, "").replace(/\/$/, "") || "dashboard";
}

export function resolveWorkbenchRouteTab(path: string): WorkbenchRouteTab | null {
  return workbenchTabByRoutePath[normalizeRoutePath(path)] || null;
}

export function routePathForWorkbenchTab(tab: WorkbenchNavigationTab): string {
  return routePathByWorkbenchTab[tab];
}
