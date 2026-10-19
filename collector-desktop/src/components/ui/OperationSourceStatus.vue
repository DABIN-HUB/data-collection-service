<template>
  <div class="operation-source" :class="`operation-source--${state}`" role="status">
    <div class="operation-source__identity">
      <span class="operation-source__badge"><i aria-hidden="true"></i>{{ labels[state] }}</span>
      <span class="operation-source__name">{{ source }}</span>
      <span v-if="detail" class="operation-source__detail">{{ detail }}</span>
    </div>
    <div class="operation-source__times">
      <span v-if="lastAttemptAt">最后尝试 <time>{{ formatTime(lastAttemptAt) }}</time></span>
      <span>最后成功 <time>{{ lastSuccessAt ? formatTime(lastSuccessAt) : '尚未成功读取' }}</time></span>
    </div>
    <div v-if="$slots.default" class="operation-source__actions"><slot /></div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  state: "pending" | "unknown" | "empty" | "loading" | "ready" | "degraded" | "error" | "disabled";
  source: string;
  lastAttemptAt?: number | string | null;
  lastSuccessAt?: number | string | null;
  detail?: string;
}>();

const labels = {
  pending: "尚未执行",
  unknown: "未知",
  empty: "已读取 · 暂无记录",
  loading: "读取中",
  ready: "已读取",
  degraded: "上次成功数据",
  error: "读取失败",
  disabled: "未启用",
};

function formatTime(value: number | string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "时间不可用";
  return date.toLocaleString("zh-CN", { hour12: false });
}
</script>

<style scoped>
.operation-source {
  display: flex;
  min-width: 0;
  padding: 10px 15px;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  color: var(--console-text-muted);
  border: 1px solid var(--console-border);
  border-radius: var(--console-radius-md);
  background: var(--console-bg-soft);
  font-size: 11px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.operation-source__identity,
.operation-source__times,
.operation-source__actions {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.operation-source__identity {
  flex: 1 1 280px;
}

.operation-source__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ops-state-unknown);
  font-weight: 600;
}

.operation-source__badge i {
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  border-radius: 50%;
  background: currentColor;
}

.operation-source--ready .operation-source__badge,
.operation-source--empty .operation-source__badge {
  color: var(--ops-state-ready);
}

.operation-source--ready,
.operation-source--empty {
  border-color: #d2e2df;
  background: var(--app-color-info-soft);
}

.operation-source--error {
  border-color: #ecd4d4;
  background: var(--app-color-error-soft);
}

.operation-source--pending .operation-source__badge,
.operation-source--disabled .operation-source__badge {
  color: var(--ops-state-pending);
}

.operation-source--degraded .operation-source__badge {
  color: var(--ops-state-degraded);
}

.operation-source--error .operation-source__badge {
  color: var(--ops-state-error);
}

.operation-source--loading .operation-source__badge {
  color: var(--ops-state-loading);
}

.operation-source__name {
  color: var(--console-text-secondary);
}

.operation-source__detail {
  flex-basis: 100%;
}

.operation-source time {
  color: var(--console-text-secondary);
  font-variant-numeric: tabular-nums;
}

@media (width <= 640px) {
  .operation-source__identity,
  .operation-source__times {
    flex-basis: 100%;
  }
}
</style>
