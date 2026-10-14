<template>
  <div class="login-page">
    <section class="login-card">
      <div class="login-form-panel">
        <div class="login-brand">
          <svg class="login-logo" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M3 21V10l6 3V7l6 4V3h5v18H3Z" />
            <path d="M6 17h2m4 0h2m3-10h1m-1 4h1m-1 6h1" />
          </svg>
          <div>
            <strong>工业数据控制台</strong>
            <p>数据采集工作台 · v{{ appStore.appVersion }}</p>
          </div>
        </div>

        <div class="login-heading">
          <h1>连接采集服务</h1>
          <p>后端服务由你启动，工作台负责配置、控制与监控。</p>
        </div>

        <el-form label-position="top" class="login-form" @submit.prevent>
          <el-form-item label="服务地址">
            <el-input v-model="serverUrl" placeholder="http://127.0.0.1:9090/collector" />
          </el-form-item>
          <el-form-item label="访问令牌">
            <el-input v-model="token" type="password" show-password placeholder="请输入访问令牌" aria-describedby="login-token-help" />
            <p id="login-token-help" class="login-token-help">令牌用于服务认证；记住后按当前运行环境保存。</p>
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="rememberToken">记住令牌</el-checkbox>
            <el-button type="primary" link :loading="testing" @click="testConnection">测试连接</el-button>
          </div>
          <el-alert v-if="message" :title="message" :type="messageType" :closable="false" />
          <el-alert
            v-if="appStore.credentialRememberUnavailable"
            title="当前系统安全存储不可用，本次令牌仅保存在当前桌面端进程内，退出后需要重新输入。"
            type="warning"
            :closable="false"
          />
          <el-button class="login-submit" type="primary" size="large" :loading="testing" @click="enterWorkbench">
            <svg class="login-submit-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M4 12h16m-6-6 6 6-6 6" />
            </svg>
            登录并进入工作台
          </el-button>
        </el-form>

        <p class="desktop-mode-note">
          当前运行平台：{{ appStore.platform }}。请先启动采集服务，工作台不会自动启动后端。
        </p>
        <footer class="login-footer">
          <span>Copyright © 2026 数据采集工作台</span>
          <el-button type="primary" link @click="openDocs">打开使用文档</el-button>
        </footer>
      </div>

      <div class="login-illustration">
        <span class="login-context-label">设备 · 数据 · 链路</span>
        <h2>让每一次采集，<br />都有清晰的状态与去向。</h2>
        <p>从协议连接、点位建模到遥测质量与云端确认，建立一个以真实业务状态为核心的操作空间。</p>
        <svg class="login-diagram" viewBox="0 0 410 220" role="img" aria-label="设备、采集数据与云端确认的链路示意，不表示实时连接状态" focusable="false">
          <g fill="none" stroke="#92afb8" stroke-width="1.4">
            <rect x="15" y="60" width="75" height="100" rx="7" />
            <path d="M28 80h49m-49 17h49m-49 17h49" />
            <circle cx="30" cy="140" r="3" />
            <circle cx="42" cy="140" r="3" />
            <path d="M90 110h55m-8-6 8 6-8 6" />
            <rect x="145" y="35" width="120" height="150" rx="8" />
            <path d="M159 60h92m-92 105h92" />
            <path d="m160 135 16-23 20 9 18-37 20 17 16-29" />
            <path d="M265 110h55m-8-6 8 6-8 6" />
            <path d="M338 140a29 29 0 0 1-1-58 26 26 0 0 1 50 10 25 25 0 0 1-4 48h-45Z" />
          </g>
          <g fill="#427c86">
            <circle cx="205" cy="196" r="3" />
            <circle cx="53" cy="175" r="3" />
            <circle cx="362" cy="153" r="3" />
          </g>
        </svg>
        <p>客户端就绪不等于服务健康。连接状态、采集健康与云端 ACK 始终分别表达。</p>
        <p v-if="appStore.configPath" class="desktop-config-path">本地配置：{{ appStore.configPath }}</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import { DEFAULT_SERVER_URL, testServerConnection } from "@/api/http";
import { useAppStore } from "@/stores/app.store";
import { useRuntimeStore } from "@/stores/runtime.store";

const router = useRouter();
const appStore = useAppStore();
const runtimeStore = useRuntimeStore();
const serverUrl = ref(DEFAULT_SERVER_URL);
const token = ref("");
const rememberToken = ref(false);
const message = ref("");
const testing = ref(false);
const messageType = computed(() => runtimeStore.connected ? "success" : "warning");

onMounted(async () => {
  await appStore.initialize();
  serverUrl.value = appStore.serverUrl;
  token.value = appStore.token;
  rememberToken.value = appStore.rememberToken;
});

async function applyConfig() {
  await appStore.updateServerUrl(serverUrl.value);
  await appStore.login(token.value, rememberToken.value);
}

async function testConnection() {
  testing.value = true;
  message.value = "";
  try {
    await applyConfig();
    const result = await testServerConnection();
    await runtimeStore.refresh();
    message.value = result.message;
  } catch (error) {
    runtimeStore.connected = false;
    runtimeStore.error = error instanceof Error ? error.message : "连接测试失败";
    message.value = runtimeStore.error;
  } finally {
    testing.value = false;
  }
}

async function enterWorkbench() {
  await testConnection();
  if (runtimeStore.connected) {
    await router.push("/dashboard");
  }
}

function openDocs() {
  window.collectorDesktop?.openExternal("https://hermes-agent.nousresearch.com/docs").catch(() => undefined);
}
</script>

<style scoped>
.login-page {
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: auto;
  color: var(--console-text-secondary);
  background: var(--console-bg);
}

.login-card {
  display: grid;
  width: 100%;
  min-height: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  background: var(--console-panel);
}

.login-form-panel,
.login-illustration {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
}

.login-form-panel {
  padding: max(35px, calc((100dvh - 531px) / 2)) 35px 35px;
  align-items: center;
  justify-content: flex-start;
  background: var(--console-panel);
}

.login-form-panel > * {
  width: 350px;
  max-width: 100%;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
}

.login-logo {
  width: 29px;
  height: 29px;
  margin-inline: 1.5px;
  flex: 0 0 auto;
  color: #84c2c1;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.65;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.login-heading h1,
.login-illustration h2 {
  margin: 0;
  color: var(--console-text-primary);
}

.login-brand strong {
  color: var(--console-text-primary);
  font-size: 16px;
  letter-spacing: -0.5px;
}

.login-brand p {
  margin: 2px 0 0;
  font-size: 11px;
}

.login-heading {
  margin: 32px 0 27px;
}

.login-heading h1 {
  font-size: 24px;
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -0.55px;
}

.login-heading p {
  margin: 8px 0 0;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.login-brand p,
.login-illustration p,
.login-footer {
  color: var(--console-text-muted);
}

.login-form {
  display: flex;
  flex-direction: column;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  padding: 0;
  font-size: 11px;
  line-height: 1.55;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 41px;
  padding: 0 11px;
  border-radius: 5px;
}

.login-form :deep(.el-input__inner) {
  height: 41px;
  font-size: 12px;
  line-height: 41px;
}

.login-token-help {
  margin: 7px 0 0;
  color: var(--console-text-muted);
  font-size: 10px;
  line-height: 1.6;
}

.login-form :deep(.el-alert) {
  margin-top: 12px;
}

.login-options,
.login-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.login-options {
  min-height: 29px;
}

.login-options :deep(.el-checkbox) {
  height: 29px;
}

.login-options :deep(.el-checkbox__label),
.login-options :deep(.el-button) {
  font-size: 11px;
}

.desktop-mode-note {
  margin: 35px 0 0;
  color: var(--console-text-muted);
  font-size: 10px;
  line-height: 1.55;
}

.login-footer {
  margin-top: 8px;
  font-size: 10px;
  line-height: 1.55;
}

.login-footer :deep(.el-button) {
  min-height: 20px;
  padding: 0;
  font-size: 10px;
}

.login-submit {
  width: 100%;
  height: 42px;
  margin-top: 22px;
  font-size: 12px;
}

.login-submit-icon {
  width: 18px;
  height: 18px;
  margin-right: 7px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.65;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.login-illustration {
  padding: 65px 55px;
  background: #e5eef0;
}

.login-illustration h2 {
  max-width: 380px;
  font-size: 26px;
  font-weight: 550;
  line-height: 1.55;
}

.login-context-label {
  width: max-content;
  margin-bottom: 20px;
  padding: 3px 8px;
  border-radius: 4px;
  color: #597583;
  background: #d4e4e7;
  font-size: 10px;
  font-weight: 500;
  line-height: 1.55;
}

.login-illustration p {
  max-width: 390px;
  margin: 16px 0 0;
  color: #637f8b;
  font-size: 12px;
  line-height: 1.9;
}

.login-diagram {
  display: block;
  width: 100%;
  max-width: 410px;
  height: 220px;
  margin: 35px 0;
  flex: 0 0 auto;
}

.desktop-config-path {
  overflow-wrap: anywhere;
}

@media (width <= 900px) {
  .login-page {
    padding: 0;
  }

  .login-card {
    grid-template-columns: 1fr;
  }

  .login-illustration {
    display: none;
  }
}
</style>
