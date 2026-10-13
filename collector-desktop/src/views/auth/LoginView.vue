<template>
  <div class="login-page">
    <section class="login-card">
      <div class="login-form-panel">
        <div class="login-brand">
          <div class="login-logo">采</div>
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
              当前运行平台：{{ appStore.platform }}。请先启动采集服务，工作台不会自动启动后端。
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
        <h2>让设备、数据与链路<br />处于同一工作视野</h2>
        <p>从协议连接、点位建模到运行排错，在一个工作台中完成工业数据采集的日常操作。</p>
        <div class="login-diagram" role="img" aria-label="设备通过采集服务向工作台提供数据的架构示意，不表示实时连接状态">
          <div class="diagram-device"><span aria-hidden="true">▤</span><strong>工业设备</strong><small>协议与点位</small></div>
          <span class="diagram-link" aria-hidden="true">→</span>
          <div class="diagram-service"><span aria-hidden="true">▥</span><strong>采集服务</strong><small>采集 · 处理 · 上报</small></div>
          <span class="diagram-link" aria-hidden="true">→</span>
          <div class="diagram-device"><span aria-hidden="true">▦</span><strong>工作台</strong><small>配置与监控</small></div>
        </div>
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
  padding: 48px max(35px, calc((50vw - 380px) / 2));
  flex-direction: column;
  justify-content: center;
}

.login-form-panel {
  background: var(--console-panel);
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
  border: 1px solid #cee3df;
  border-radius: 8px;
  color: var(--console-primary);
  background: var(--app-color-info-soft);
  font-size: 20px;
  font-weight: 700;
}

.login-heading h1,
.login-illustration h2 {
  margin: 0;
  color: var(--console-text-primary);
}

.login-brand strong {
  color: var(--console-text-primary);
  font-size: 18px;
}

.login-brand p {
  margin: 5px 0 0;
  font-size: 11px;
}

.login-heading {
  margin: 12px 0 25px;
}

.login-heading h1 {
  font-size: 24px;
  font-weight: 650;
}

.login-heading p {
  margin: 9px 0 0;
  color: var(--console-text-muted);
  font-size: 12px;
  line-height: 1.8;
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
  flex-wrap: wrap;
}

footer {
  margin-top: 30px;
  font-size: 10px;
}

.login-submit {
  width: 100%;
}

.login-illustration {
  padding: 65px 55px;
  background: #e5eef0;
}

.login-illustration h2 {
  font-size: 26px;
  font-weight: 550;
  line-height: 1.5;
}

.login-illustration p {
  max-width: 390px;
  font-size: 12px;
  line-height: 1.9;
}

.login-diagram {
  display: flex;
  width: 100%;
  max-width: 470px;
  margin: 40px 0;
  align-items: center;
  gap: 8px;
}

.diagram-device,
.diagram-service {
  display: grid;
  min-width: 0;
  padding: 20px 10px;
  flex: 1;
  gap: 8px;
  border: 1px solid #bbcfd6;
  border-radius: 8px;
  background: #f5f9fa;
  text-align: center;
}

.diagram-service {
  background: #d4e5e6;
}

.login-diagram strong {
  font-size: 12px;
}

.login-diagram small {
  color: #637f8b;
  font-size: 10px;
}

.diagram-device > span,
.diagram-service > span {
  color: #52818b;
  font-size: 28px;
}

.diagram-link {
  color: #7698a3;
}

.desktop-config-path {
  overflow-wrap: anywhere;
}

@media (max-width: 840px) {
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
    padding: 32px 24px;
  }
}
</style>
