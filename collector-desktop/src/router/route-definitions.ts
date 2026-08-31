import type { RouteRecordRaw } from "vue-router";

import { RouteNames } from "./route-names";

const AppShell = () => import("@/app/AppShell.vue");
const DashboardView = () => import("@/views/dashboard/DashboardView.vue");
const RealtimeView = () => import("@/views/realtime/RealtimeView.vue");
const HistoryView = () => import("@/views/history/HistoryView.vue");
const LogView = () => import("@/views/log/LogView.vue");
const AlarmView = () => import("@/views/alarm/AlarmView.vue");
const NetworkView = () => import("@/views/network/NetworkView.vue");
const CloudView = () => import("@/views/cloud/CloudView.vue");
const DiagnosticView = () => import("@/views/diagnostic/DiagnosticView.vue");
const CollectionView = () => import("@/views/collection/CollectionView.vue");
const DeviceListView = () => import("@/views/device/DeviceListView.vue");
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
      { path: "history", name: RouteNames.HISTORY, component: HistoryView },
      { path: "alarm", name: RouteNames.ALARM, component: AlarmView },
      { path: "device", name: RouteNames.DEVICE, component: DeviceListView },
      { path: "device/workbench", name: RouteNames.DEVICE_WORKBENCH, component: LegacyConsoleView },
      { path: "collect", name: RouteNames.COLLECTION, component: CollectionView },
      { path: "cloud", name: RouteNames.CLOUD, component: CloudView },
      { path: "diagnostic", name: RouteNames.DIAGNOSTIC, component: DiagnosticView },
      { path: "log", name: RouteNames.LOG, component: LogView },
      { path: "network", name: RouteNames.NETWORK, component: NetworkView },
      { path: "control", name: RouteNames.CONTROL, component: LegacyConsoleView },
      { path: "shadow", name: RouteNames.SHADOW, component: LegacyConsoleView }
    ]
  },
  { path: "/:pathMatch(.*)*", redirect: "/" }
];
