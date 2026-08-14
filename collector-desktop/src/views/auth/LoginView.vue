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
          <el-form-item label="接口访问令牌">
            <el-input v-model="token" type="password" show-password placeholder="请输入 X-Collector-Token" />
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="rememberToken">记住令牌</el-checkbox>
            <el-button type="primary" link :loading="testing" @click="testConnection">测试连接</el-button>
          </div>
          <el-alert v-if="message" :title="message" :type="messageType" :closable="false" />
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
  appStore.login(token.value, rememberToken.value);
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
