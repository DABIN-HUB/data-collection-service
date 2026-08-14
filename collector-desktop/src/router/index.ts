import { createRouter, createWebHashHistory } from "vue-router";

import { appRouteDefinitions } from "./route-definitions";

export const router = createRouter({
  history: createWebHashHistory(),
  routes: appRouteDefinitions
});
