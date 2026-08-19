import type { RouteRecordRaw } from "vue-router";

import LegacyConsoleView from "@/views/legacy/LegacyConsoleView.vue";

export const appRouteDefinitions: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/auth/LoginView.vue")
  },
  {
    path: "/",
    component: LegacyConsoleView,
    children: [
      { path: "", redirect: "/dashboard" },
      { path: "dashboard", name: "dashboard", component: LegacyConsoleView },
      { path: "realtime", name: "realtime", component: LegacyConsoleView },
      { path: "history", name: "history", component: LegacyConsoleView },
      { path: "alarm", name: "alarm", component: LegacyConsoleView },
      { path: "device", name: "device", component: LegacyConsoleView },
      { path: "collect", name: "collect", component: LegacyConsoleView },
      { path: "cloud", name: "cloud", component: LegacyConsoleView },
      { path: "diagnostic", name: "diagnostic", component: LegacyConsoleView },
      { path: "log", name: "log", component: LegacyConsoleView },
      { path: "network", name: "network", component: LegacyConsoleView },
      { path: "control", name: "control", component: LegacyConsoleView },
      { path: "shadow", name: "shadow", component: LegacyConsoleView }
    ]
  },
  { path: "/:pathMatch(.*)*", redirect: "/" }
];
