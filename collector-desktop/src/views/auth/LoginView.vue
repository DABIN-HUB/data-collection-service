<template>
  <div class="login-page">
    <section class="login-card">
      <div class="login-form-panel">
        <p class="login-connection-caption">连接采集服务</p>

        <div class="login-heading">
          <h1>进入工作台</h1>
          <p>配置目标服务并验证访问能力。这里不是账号密码登录。</p>
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

        <div class="login-connection-boundary" role="note" aria-label="连接操作边界">
          <strong>测试连接会应用地址与凭据</strong>
          <p>测试连接与登录均会应用当前地址和令牌；是否记住遵循本页选项。</p>
          <p>接口访问结果与业务健康分开判断，连接成功不代表采集服务健康。</p>
        </div>

        <p class="desktop-mode-note">
          当前运行平台：{{ appStore.platform }}。请先启动采集服务，工作台不会自动启动后端。
        </p>
        <footer class="login-footer">
          <span>数据采集工作台 · v{{ appStore.appVersion }} · © 2026</span>
          <el-button type="primary" link @click="openDocs">打开使用文档</el-button>
        </footer>
      </div>

      <div class="login-illustration">
        <span class="login-reference-mark" aria-hidden="true">◇</span>
        <h2>让每一次采集，都有可追溯的上下文。</h2>
        <p>面向工业现场的连接、采集、告警与上报工作台。把设备身份、数据来源与操作风险放在同一张工作面上。</p>
        <div class="login-signal">
          <strong>精密控制台 / Precision Workbench</strong><br />
          本地客户端就绪 ≠ 服务健康<br />
          接口访问结果与业务健康分开表达
        </div>
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
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
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
  grid-column: 2;
  grid-row: 1;
  padding: 40px;
  align-items: center;
  justify-content: center;
  background: #f4f7f8;
}

.login-form-panel > * {
  width: 430px;
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
  margin: 0 0 28px;
}

.login-heading h1 {
  margin: 0;
  color: var(--console-text-primary);
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
.login-footer {
  color: var(--console-text-muted);
}

.login-connection-caption {
  margin: 0 0 10px;
  color: var(--console-text-muted);
  font-size: 12px;
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

.login-connection-boundary {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--app-color-border);
  color: var(--console-text-muted);
  font-size: 11px;
  line-height: 1.65;
}

.login-connection-boundary strong {
  color: var(--console-text-primary);
  font-weight: 600;
}

.login-connection-boundary p {
  margin: 7px 0 0;
}

.desktop-mode-note {
  margin: 18px 0 0;
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
  position: relative;
  grid-column: 1;
  grid-row: 1;
  padding: 72px;
  overflow: hidden;
  justify-content: flex-start;
  color: #d9e5e8;
  background: var(--app-color-sidebar);
}

.login-illustration h2 {
  margin: 0;
  max-width: 460px;
  color: #f4fbfa;
  font-size: 33px;
  font-weight: 650;
  line-height: 1.25;
  letter-spacing: -0.04em;
}

.login-reference-mark {
  display: grid;
  width: 30px;
  height: 30px;
  margin-bottom: 14px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid #7ac4c1;
  color: #9ee0d9;
  font-weight: 700;
  letter-spacing: -2px;
}

.login-illustration p {
  max-width: 430px;
  margin: 16px 0 0;
  color: #a4b9be;
  font-size: 13px;
  line-height: 1.8;
}

.login-signal {
  margin-top: 82px;
  padding-left: 16px;
  border-left: 2px solid #6bc7c1;
  color: #aac3c5;
  font-size: 12px;
  line-height: 1.8;
}

.login-illustration::after {
  position: absolute;
  right: -100px;
  bottom: -100px;
  width: 420px;
  height: 420px;
  border: 1px solid rgb(116 205 199 / 25%);
  border-radius: 50%;
  box-shadow: 0 0 0 35px rgb(116 205 199 / 6%), 0 0 0 70px rgb(116 205 199 / 4%);
  content: '';
  pointer-events: none;
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

  .login-form-panel {
    grid-column: 1;
    grid-row: 1;
    padding: 32px 24px;
  }
}
</style>
