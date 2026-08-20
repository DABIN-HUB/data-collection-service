<template>
  <section class="exact-surface diagnostic-detail-panel">
    <div class="exact-surface-head">
      <h2>诊断详情增强</h2>
      <span>/monitor/cache · /monitor/devices · /monitor/perf/detail · /monitor/errors · /monitor/storage</span>
    </div>
    <div class="exact-diagnostic-cards diagnostic-detail-cards">
      <div class="exact-diagnostic-card">
        <span>缓存命中率</span>
        <strong>{{ cacheDetail.hitRateText }}</strong>
        <small>L1 {{ cacheDetail.level1Text }} / L2 {{ cacheDetail.level2Text }} / Miss {{ cacheDetail.missRateText }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>设备连接</span>
        <strong>{{ connectedCount }}/{{ connectionRows.length }}</strong>
        <small>缺失连接 {{ missingCount }} 个</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>线程池拒绝</span>
        <strong>{{ performanceDetail.rejectedTotal }}</strong>
        <small>过载分片 {{ performanceDetail.overloadedCount }} 个</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>异常统计 Top</span>
        <strong>{{ exceptionDetail.totalText }}</strong>
        <small>{{ exceptionDetail.topCategories[0]?.name || '无异常分类' }}</small>
      </div>
      <div class="exact-diagnostic-card">
        <span>历史存储状态</span>
        <strong>{{ storageDetail.statusText }}</strong>
        <small>{{ storageDetail.message }}</small>
      </div>
    </div>
    <div class="diagnostic-detail-grid">
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>缓存服务明细</h2><span>{{ cacheDetail.status }}</span></div>
        <div class="modao-property-grid">
          <div class="modao-property-item"><span>读 / 写</span><strong>{{ cacheDetail.readWriteText }}</strong></div>
          <div class="modao-property-item"><span>总命中率</span><strong>{{ cacheDetail.hitRateText }}</strong></div>
          <div class="modao-property-item"><span>一级缓存</span><strong>{{ cacheDetail.level1Text }}</strong></div>
          <div class="modao-property-item"><span>二级缓存</span><strong>{{ cacheDetail.level2Text }}</strong></div>
        </div>
      </section>
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>性能详情</h2><span>{{ performanceDetail.timeSliceText }}</span></div>
        <div class="modao-property-grid">
          <div class="modao-property-item"><span>拒绝总数</span><strong>{{ performanceDetail.rejectedTotal }}</strong></div>
          <div class="modao-property-item"><span>过载分片</span><strong>{{ performanceDetail.overloadedCount }}</strong></div>
          <div class="modao-property-item wide"><span>重连统计</span><strong>{{ performanceDetail.reconnectText }}</strong></div>
        </div>
      </section>
    </div>
    <section class="exact-table-card diagnostic-connection-table">
      <div class="exact-table-title"><h2>设备连接指标</h2><span>/monitor/devices</span></div>
      <table>
        <thead><tr><th>设备</th><th>状态</th><th>连接</th><th>成功率</th><th>收/发字节</th><th>空闲</th><th>错误</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-if="connectionRows.length === 0"><td colspan="8" class="exact-empty">暂无设备连接指标</td></tr>
          <tr v-for="row in connectionRows" :key="row.deviceId">
            <td><code>{{ row.deviceId }}</code></td>
            <td>{{ row.statusText }}</td>
            <td><span class="status-badge" :class="row.tone">{{ row.connectedText }}</span></td>
            <td>{{ row.successRateText }}</td>
            <td>{{ row.bytesText }}</td>
            <td>{{ row.idleTimeText }}</td>
            <td>{{ row.errors }}</td>
            <td>{{ row.missing ? '缺失连接或仅存在期望配置' : '连接指标已采集' }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <div class="diagnostic-detail-grid">
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>异常统计 Top</h2><span>/monitor/errors</span></div>
        <div class="modao-risk-list">
          <div v-if="exceptionDetail.topCategories.length === 0" class="empty-state compact">暂无异常分类统计</div>
          <div v-for="item in exceptionDetail.topCategories.slice(0, 5)" :key="item.name" class="modao-risk-item"><strong>{{ item.name }}</strong><small>{{ item.count }} 次</small></div>
        </div>
      </section>
      <section class="exact-table-card diagnostic-sub-card">
        <div class="exact-table-title"><h2>最慢设备 Top</h2><span>/monitor/perf/detail</span></div>
        <div class="modao-risk-list">
          <div v-if="performanceDetail.slowestDevices.length === 0" class="empty-state compact">暂无慢设备统计</div>
          <div v-for="item in performanceDetail.slowestDevices.slice(0, 5)" :key="item.deviceId" class="modao-risk-item"><strong>{{ item.deviceId }}</strong><small>{{ item.costMs }} ms</small></div>
        </div>
      </section>
    </div>
    <section class="exact-table-card diagnostic-exception-table">
      <div class="exact-table-title"><h2>最近异常</h2><span>{{ exceptionDetail.totalText }}</span></div>
      <table>
        <thead><tr><th>时间</th><th>设备</th><th>点位</th><th>分类</th><th>消息</th></tr></thead>
        <tbody>
          <tr v-if="exceptionDetail.recent.length === 0"><td colspan="5" class="exact-empty">暂无最近异常</td></tr>
          <tr v-for="item in exceptionDetail.recent.slice(0, 8)" :key="`${item.timestamp || '-'}-${item.deviceId}-${item.pointId}-${item.category}`">
            <td>{{ formatTime(item.timestamp) }}</td>
            <td>{{ item.deviceId }}</td>
            <td>{{ item.pointId }}</td>
            <td>{{ item.category }}</td>
            <td>{{ item.message }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";

import { buildCacheDetail, buildDeviceConnectionRows, buildExceptionDetail, buildPerformanceDetail, buildStorageDetail } from "./diagnostic-detail-utils";

const props = defineProps<{
  cacheMetrics: unknown;
  deviceMetrics: unknown;
  performanceMetrics: unknown;
  exceptionStats: unknown;
  storageMetrics: unknown;
}>();

const cacheDetail = computed(() => buildCacheDetail(props.cacheMetrics));
const connectionRows = computed(() => buildDeviceConnectionRows(props.deviceMetrics));
const performanceDetail = computed(() => buildPerformanceDetail(props.performanceMetrics));
const exceptionDetail = computed(() => buildExceptionDetail(props.exceptionStats));
const storageDetail = computed(() => buildStorageDetail(props.storageMetrics));
const connectedCount = computed(() => connectionRows.value.filter((row) => row.connectedText === "已连接").length);
const missingCount = computed(() => connectionRows.value.filter((row) => row.missing).length);

function formatTime(value: unknown): string {
  if (!value) {
    return "-";
  }
  const date = new Date(typeof value === "number" ? value : String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}
</script>
