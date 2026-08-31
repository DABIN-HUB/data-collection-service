export interface DeviceListEmptyTextInput {
  loading: boolean;
  errorMessage: string;
  hasFilters: boolean;
}

export function buildDeviceListEmptyText(input: DeviceListEmptyTextInput): string {
  const errorMessage = input.errorMessage.trim();
  if (errorMessage) {
    if (errorMessage.includes("令牌") || errorMessage.includes("401") || errorMessage.toLowerCase().includes("credential")) {
      return "设备配置加载失败：接口访问令牌缺失或无效，请先在顶部运维令牌处保存令牌后刷新。";
    }
    return `设备配置加载失败：${errorMessage}`;
  }
  if (input.loading) {
    return "正在加载设备配置...";
  }
  return input.hasFilters ? "没有符合筛选条件的设备" : "当前没有设备配置，请新增本地设备或同步远端配置";
}
