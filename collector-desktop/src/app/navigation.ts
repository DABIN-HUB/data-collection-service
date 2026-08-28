import alertCircleIcon from "@/assets/legacy-icons/alert-circle.svg";
import chartTimelineIcon from "@/assets/legacy-icons/chart-timeline-variant.svg";
import cloudUploadIcon from "@/assets/legacy-icons/cloud-upload.svg";
import databaseCogIcon from "@/assets/legacy-icons/database-cog.svg";
import fileDocumentIcon from "@/assets/legacy-icons/file-document-outline.svg";
import monitorDashboardIcon from "@/assets/legacy-icons/monitor-dashboard.svg";
import networkOutlineIcon from "@/assets/legacy-icons/network-outline.svg";
import routerWirelessIcon from "@/assets/legacy-icons/router-wireless.svg";
import viewDashboardIcon from "@/assets/legacy-icons/view-dashboard.svg";
import { RouteNames, type RouteName } from "@/router/route-names";

export type AppNavigationKey = "overview" | "realtime" | "history" | "alarm" | "device" | "collect" | "cloud" | "diag" | "log" | "network";

export interface AppNavigationItem {
  key: AppNavigationKey;
  label: string;
  icon: string;
  path: string;
  routeName: RouteName;
  activePaths?: string[];
}

export interface AppNavigationGroup {
  title: string;
  items: AppNavigationItem[];
}

export const navigationGroups: AppNavigationGroup[] = [
  {
    title: "运行",
    items: [
      { key: "overview", label: "概览", icon: viewDashboardIcon, path: "/dashboard", routeName: RouteNames.DASHBOARD },
      { key: "realtime", label: "实时数据", icon: chartTimelineIcon, path: "/realtime", routeName: RouteNames.REALTIME },
      { key: "history", label: "历史趋势", icon: chartTimelineIcon, path: "/history", routeName: RouteNames.HISTORY },
      { key: "alarm", label: "告警总览", icon: alertCircleIcon, path: "/alarm", routeName: RouteNames.ALARM }
    ]
  },
  {
    title: "配置",
    items: [
      { key: "device", label: "设备管理", icon: routerWirelessIcon, path: "/device", routeName: RouteNames.DEVICE, activePaths: ["/device/workbench", "/control", "/shadow"] },
      { key: "collect", label: "采集配置", icon: databaseCogIcon, path: "/collect", routeName: RouteNames.COLLECTION },
      { key: "cloud", label: "云平台配置", icon: cloudUploadIcon, path: "/cloud", routeName: RouteNames.CLOUD }
    ]
  },
  {
    title: "诊断",
    items: [
      { key: "diag", label: "系统诊断", icon: monitorDashboardIcon, path: "/diagnostic", routeName: RouteNames.DIAGNOSTIC },
      { key: "log", label: "日志", icon: fileDocumentIcon, path: "/log", routeName: RouteNames.LOG },
      { key: "network", label: "网络检测", icon: networkOutlineIcon, path: "/network", routeName: RouteNames.NETWORK }
    ]
  }
];
