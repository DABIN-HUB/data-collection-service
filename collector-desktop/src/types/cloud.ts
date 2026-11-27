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

export type CloudReportResultStatus = "PUBLISHED" | "ACK_CONFIRMED" | "FAILED";

export interface CloudReportResultItem {
  messageId: string;
  localDeviceIds?: string[];
  cloudDeviceId?: string;
  gatewayDeviceId?: string;
  status?: CloudReportResultStatus | string;
  publishedAt?: number;
  ackAt?: number;
  completedAt?: number;
  attempts?: number;
  createdAt?: number;
  errorMessage?: string;
}

export interface CloudReportResultPage {
  items: CloudReportResultItem[];
  ackCount: number | null;
  total: number | null;
  complete: boolean;
  pendingCount: number | null;
  missingCount: number | null;
}

export interface CloudReportResultQueryParams {
  deviceId?: string;
  startTs?: number;
  endTs?: number;
  page?: number;
  limit?: number;
}

export interface CloudFlushResponse { accepted: boolean; pendingBefore: number; isolated: number; triggeredAt: number; }
export interface CloudTestResponse { enabled: boolean; configured: boolean; connected?: boolean | null; message?: string; checkedAt: number; }
