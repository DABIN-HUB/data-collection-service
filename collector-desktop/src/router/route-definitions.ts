import type { RouteRecordRaw } from "vue-router";

import { RouteNames } from "./route-names";

const AppShell = () => import("@/app/AppShell.vue");
const DashboardView = () => import("@/views/dashboard/DashboardView.vue");
const RealtimeView = () => import("@/views/realtime/RealtimeView.vue");
const LogView = () => import("@/views/log/LogView.vue");
const LegacyConsoleView = () => import("@/views/legacy/LegacyConsoleView.vue");

export const appRouteDefinitions: RouteRecordRaw[] = [
  {
    path: "/login",
    name: RouteNames.LOGIN,
    component: () => import("@/views/auth/LoginView.vue")
  },
  {
    path: "/",
    component: AppShell,
    children: [
      { path: "", redirect: "/dashboard" },
      { path: "dashboard", name: RouteNames.DASHBOARD, component: DashboardView },
      { path: "realtime", name: RouteNames.REALTIME, component: RealtimeView },
      { path: "history", name: RouteNames.HISTORY, component: LegacyConsoleView },
      { path: "alarm", name: RouteNames.ALARM, component: LegacyConsoleView },
      { path: "device", name: RouteNames.DEVICE, component: LegacyConsoleView },
      { path: "device/workbench", name: RouteNames.DEVICE_WORKBENCH, component: LegacyConsoleView },
      { path: "collect", name: RouteNames.COLLECTION, component: LegacyConsoleView },
      { path: "cloud", name: RouteNames.CLOUD, component: LegacyConsoleView },
      { path: "diagnostic", name: RouteNames.DIAGNOSTIC, component: LegacyConsoleView },
      { path: "log", name: RouteNames.LOG, component: LogView },
      { path: "network", name: RouteNames.NETWORK, component: LegacyConsoleView },
      { path: "control", name: RouteNames.CONTROL, component: LegacyConsoleView },
      { path: "shadow", name: RouteNames.SHADOW, component: LegacyConsoleView }
    ]
  },
  { path: "/:pathMatch(.*)*", redirect: "/" }
];
