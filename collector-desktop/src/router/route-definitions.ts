import type { RouteRecordRaw } from "vue-router";

import WorkbenchLayout from "@/layouts/WorkbenchLayout.vue";

export const appRouteDefinitions: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/auth/LoginView.vue")
  },
  {
    path: "/",
    component: WorkbenchLayout,
    children: [
      { path: "", redirect: "/dashboard" },
      { path: "dashboard", name: "dashboard", component: () => import("@/views/dashboard/DashboardView.vue") },
      { path: "realtime", name: "realtime", component: () => import("@/views/realtime/RealtimeQueryView.vue") },
      { path: "history", name: "history", component: () => import("@/views/history/PointHistoryView.vue") },
      { path: "alarm", name: "alarm", component: () => import("@/views/alarm/AlarmManagementView.vue") },
      { path: "device", name: "device", component: () => import("@/views/device/DeviceWorkbenchView.vue") },
      { path: "collect", name: "collect", component: () => import("@/views/collect/CollectConfigView.vue") },
      { path: "cloud", name: "cloud", component: () => import("@/views/cloud/CloudConfigView.vue") },
      { path: "diagnostic", name: "diagnostic", component: () => import("@/views/diagnostic/SystemDiagnosticView.vue") },
      { path: "log", name: "log", component: () => import("@/views/log/RuntimeLogView.vue") },
      { path: "network", name: "network", component: () => import("@/views/network/NetworkDiagnosticView.vue") },
      { path: "control", name: "control", component: () => import("@/views/control/ManualControlView.vue") },
      { path: "shadow", name: "shadow", component: () => import("@/views/shadow/DeviceShadowView.vue") }
    ]
  }
];
