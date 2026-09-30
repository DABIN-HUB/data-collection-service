<template>
  <div class="login-page">
    <section class="login-card">
      <div class="login-form-panel">
        <div class="login-brand">
          <div class="login-logo">采</div>
          <div>
            <h1>数据采集工作台</h1>
            <p>Collector Studio · v{{ appStore.appVersion }}</p>
          </div>
        </div>

        <el-form label-position="top" class="login-form" @submit.prevent>
          <el-form-item label="服务地址">
            <el-input v-model="serverUrl" placeholder="http://127.0.0.1:9090/collector" />
          </el-form-item>
          <el-form-item label="访问令牌">
            <el-input v-model="token" type="password" show-password placeholder="请输入访问令牌" />
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
          <el-alert class="desktop-mode-alert" type="info" :closable="false">
            <template #title>
              Electron 平台：{{ appStore.platform }}；后端由用户手动启动，桌面端不会自动拉起 Spring Boot jar。
            </template>
          </el-alert>
          <el-button class="login-submit" type="primary" size="large" :loading="testing" @click="enterWorkbench">
            登录并进入工作台
          </el-button>
        </el-form>

        <footer>
          <span>Copyright © 2026 数据采集工作台</span>
          <el-button type="primary" link @click="openDocs">打开使用文档</el-button>
        </footer>
      </div>

      <div class="login-illustration">
        <div class="server-stack">
          <div class="server-card large"></div>
          <div class="server-card medium"></div>
          <div class="server-card small"></div>
          <span class="cube c1"></span>
          <span class="cube c2"></span>
          <span class="cube c3"></span>
        </div>
        <h2>工业协议采集客户端</h2>
        <p>后端 Spring Boot 服务由你手动启动，桌面端只负责配置、控制、监控和展示。</p>
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
  min-height: 100%;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--console-text-secondary);
  background: radial-gradient(circle at top left, rgba(59, 130, 246, 0.2), transparent 32%), var(--console-bg);
}

.login-card {
  display: grid;
  width: min(960px, 100%);
  overflow: hidden;
  grid-template-columns: minmax(360px, 1fr) minmax(300px, 0.85fr);
  border: 1px solid var(--console-border-soft);
  border-radius: var(--console-radius-panel);
  background: var(--console-panel);
  box-shadow: var(--console-overlay-shadow);
}

.login-form-panel,
.login-illustration {
  padding: 32px;
}

.login-form-panel {
  background: linear-gradient(180deg, rgba(37, 46, 63, 0.96), rgba(26, 35, 50, 0.96));
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
}

.login-logo {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border: 1px solid rgba(59, 130, 246, 0.48);
  border-radius: 14px;
  color: var(--console-text-primary);
  background: rgba(37, 99, 235, 0.32);
  font-size: 20px;
  font-weight: 700;
}

.login-brand h1,
.login-illustration h2 {
  margin: 0;
  color: var(--console-text-primary);
}

.login-brand p,
.login-illustration p,
footer {
  color: var(--console-text-muted);
}

.login-form {
  display: grid;
  gap: 12px;
}

.login-options,
footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.login-submit {
  width: 100%;
}

.login-illustration {
  border-left: 1px solid var(--console-border-soft);
  background: linear-gradient(145deg, rgba(13, 27, 42, 0.9), rgba(16, 29, 49, 0.94));
}

.server-stack {
  position: relative;
  height: 180px;
  margin-bottom: 24px;
}

.server-card,
.cube {
  position: absolute;
  display: block;
  border: 1px solid rgba(82, 121, 166, 0.58);
  border-radius: var(--console-radius-lg);
  background: rgba(18, 38, 59, 0.86);
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.28);
}

.server-card.large {
  inset: 18px 32px auto 8px;
  height: 76px;
}

.server-card.medium {
  right: 12px;
  bottom: 44px;
  width: 62%;
  height: 58px;
}

.server-card.small {
  bottom: 8px;
  left: 34px;
  width: 42%;
  height: 42px;
}

.cube {
  width: 18px;
  height: 18px;
  border-color: rgba(34, 211, 238, 0.52);
  background: rgba(34, 211, 238, 0.18);
}

.cube.c1 {
  top: 8px;
  right: 36px;
}

.cube.c2 {
  top: 102px;
  left: 8px;
}

.cube.c3 {
  right: 26px;
  bottom: 12px;
}

.desktop-config-path {
  overflow-wrap: anywhere;
}

@media (max-width: 840px) {
  .login-page {
    padding: 20px;
  }

  .login-card {
    grid-template-columns: 1fr;
  }

  .login-illustration {
    border-top: 1px solid var(--console-border-soft);
    border-left: 0;
  }
}
</style>
