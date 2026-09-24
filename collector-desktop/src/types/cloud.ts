export interface CloudOutboxListItem {
  messageId: string;
  localDeviceId?: string;
  cloudDeviceId?: string;
  gatewayDeviceId?: string;
  status?: string;
  retryCount?: number;
  createdAt?: number;
  nextAttemptAt?: number;
  lastError?: string;
}
export interface CloudOutboxDetail { summary: CloudOutboxListItem; shadowVersion?: number; windowStart?: number; windowEnd?: number; reportData?: unknown; commits?: unknown[]; }
export interface CloudFlushResponse { accepted: boolean; pendingBefore: number; isolated: number; triggeredAt: number; }
export interface CloudTestResponse { enabled: boolean; configured: boolean; connected?: boolean | null; message?: string; checkedAt: number; }
