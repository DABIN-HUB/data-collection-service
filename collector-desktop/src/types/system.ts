export interface SystemCapabilities {
  realtime: {
    browserTransport: "HTTP_POLLING" | string;
    websocketAvailable: boolean;
    sseAvailable: boolean;
    pollingAvailable: boolean;
  };
  history: { available: boolean; backend?: string; reason?: string | null };
  control: { available: boolean; readbackSupported: boolean };
  shadow: { available: boolean };
  cloud: { monitoringAvailable: boolean; managementAvailable: boolean };
}
