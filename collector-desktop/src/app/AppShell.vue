<template>
  <div class="shell app-shell">
    <div v-if="narrowScreen && navigationOpen" class="app-shell__backdrop" aria-hidden="true" @click="closeNavigation"></div>
    <div
      id="app-navigation"
      ref="navigationPanel"
      class="app-shell__navigation"
      :class="{ 'is-drawer': narrowScreen }"
      :hidden="narrowScreen && !navigationOpen"
      :inert="narrowScreen && !navigationOpen"
      :role="narrowScreen ? 'dialog' : undefined"
      :aria-modal="narrowScreen && navigationOpen ? true : undefined"
      :aria-labelledby="narrowScreen ? 'app-navigation-title' : undefined"
      tabindex="-1"
    >
      <div v-if="narrowScreen" class="app-shell__drawer-heading">
        <strong id="app-navigation-title">控制台导航</strong>
        <button type="button" aria-label="关闭控制台导航" @click="closeNavigation">×</button>
      </div>
      <AppSidebar @navigate="closeNavigation" />
    </div>
    <main ref="mainPanel" class="content app-shell__main" :inert="narrowScreen && navigationOpen" tabindex="-1">
      <AppTopbar :navigation-open="navigationOpen" @open-navigation="openNavigation" />
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterView, useRoute } from "vue-router";

import AppSidebar from "@/app/AppSidebar.vue";
import AppTopbar from "@/app/AppTopbar.vue";
import { useAppStore } from "@/stores/app.store";

const appStore = useAppStore();
const route = useRoute();
const mediaQuery = window.matchMedia("(max-width: 900px)");
const narrowScreen = ref(mediaQuery.matches);
const navigationOpen = ref(false);
const navigationPanel = ref<HTMLElement | null>(null);
const mainPanel = ref<HTMLElement | null>(null);
let previousBodyOverflow: string | null = null;

function unlockBackground() {
  if (previousBodyOverflow !== null) {
    document.body.style.overflow = previousBodyOverflow;
    previousBodyOverflow = null;
  }
}

function visibleFocusableElements(): HTMLElement[] {
  return Array.from(navigationPanel.value?.querySelectorAll<HTMLElement>(
    'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]',
  ) || []).filter((element) => {
    if (element.tabIndex < 0 || element.matches(":disabled") || element.closest("[hidden], [inert]")) return false;
    if (element.getClientRects().length === 0) return false;
    const visibility = getComputedStyle(element).visibility;
    if (visibility === "hidden" || visibility === "collapse") return false;
    if (typeof element.checkVisibility === "function" && !element.checkVisibility()) return false;

    // 折叠内容可能仍有布局矩形；逐层仅保留首个直属 summary 及其子树，也兼容缺少 checkVisibility 的运行时。
    for (let ancestor = element.parentElement; ancestor; ancestor = ancestor.parentElement) {
      if (ancestor instanceof HTMLDetailsElement && !ancestor.open) {
        const summary = ancestor.querySelector(":scope > summary");
        if (!summary?.contains(element)) return false;
      }
    }
    return true;
  });
}

function focusNavigation() {
  const elements = visibleFocusableElements();
  const currentLink = elements.find((element) => element.matches("a.is-active"));
  const target = currentLink || elements[0] || navigationPanel.value;
  target?.focus();
  if (target && document.activeElement !== target) navigationPanel.value?.focus();
}

async function openNavigation() {
  if (!narrowScreen.value || navigationOpen.value) return;
  navigationOpen.value = true;
  previousBodyOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  await nextTick();
  if (navigationOpen.value) focusNavigation();
}

async function closeNavigation() {
  if (!navigationOpen.value) return;
  navigationOpen.value = false;
  unlockBackground();
  await nextTick();
  const trigger = mainPanel.value?.querySelector<HTMLElement>("[data-navigation-trigger]");
  if (trigger && trigger.getClientRects().length > 0) {
    trigger.focus();
  } else {
    const currentLink = navigationPanel.value?.querySelector<HTMLElement>("a.is-active");
    (currentLink || mainPanel.value)?.focus();
  }
}

async function handleViewportChange(event: MediaQueryListEvent) {
  const focusWasInNavigation = navigationPanel.value?.contains(document.activeElement);
  narrowScreen.value = event.matches;
  if (navigationOpen.value) {
    await closeNavigation();
  } else if (event.matches && focusWasInNavigation) {
    await nextTick();
    mainPanel.value?.querySelector<HTMLElement>("[data-navigation-trigger]")?.focus();
  }
}

function handleNavigationKeydown(event: KeyboardEvent) {
  if (!narrowScreen.value || !navigationOpen.value) return;
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    void closeNavigation();
  } else if (event.key === "Tab") {
    const elements = visibleFocusableElements();
    const first = elements[0];
    const last = elements[elements.length - 1];
    if (!first || !last) {
      event.preventDefault();
      navigationPanel.value?.focus();
    } else if (!elements.some((element) => element === document.activeElement)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}

function containNavigationFocus(event: FocusEvent) {
  if (narrowScreen.value && navigationOpen.value && !navigationPanel.value?.contains(event.target as Node)) {
    focusNavigation();
  }
}

watch(() => route.fullPath, () => { void closeNavigation(); }, { flush: "post" });

onMounted(() => {
  mediaQuery.addEventListener("change", handleViewportChange);
  document.addEventListener("keydown", handleNavigationKeydown, true);
  document.addEventListener("focusin", containNavigationFocus);
  void appStore.initialize();
});

onBeforeUnmount(() => {
  mediaQuery.removeEventListener("change", handleViewportChange);
  document.removeEventListener("keydown", handleNavigationKeydown, true);
  document.removeEventListener("focusin", containNavigationFocus);
  unlockBackground();
});
</script>

<style scoped>
.app-shell {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--console-text-secondary);
  background: var(--console-bg);
}

.app-shell__main {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overflow: hidden;
}

.app-shell__navigation {
  display: flex;
  min-height: 0;
  flex: 0 0 var(--app-sidebar-width);
  flex-direction: column;
}

.app-shell__navigation[hidden] {
  display: none;
}

.app-shell__backdrop {
  position: fixed;
  z-index: 120;
  inset: 0;
  background: rgba(2, 6, 15, 0.7);
}

.app-shell__drawer-heading {
  display: flex;
  padding: 8px 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--console-border-soft);
  color: var(--console-text-secondary);
  font-size: 14px;
}

.app-shell__drawer-heading button {
  width: 44px;
  min-height: 44px;
  font-size: 22px;
}

.app-shell__drawer-heading button:focus-visible {
  outline: 2px solid var(--app-control-border-focus);
  outline-offset: 2px;
}

@media (width <= 900px) {
  .app-shell__navigation.is-drawer {
    position: fixed;
    z-index: 121;
    inset: 0 auto 0 0;
    width: min(280px, calc(100vw - 48px));
    height: 100%;
    border-right: 1px solid var(--console-border);
    background: var(--app-color-sidebar);
    box-shadow: var(--console-overlay-shadow);
  }

  .app-shell__navigation :deep(.app-sidebar) {
    min-height: 0;
    flex: 1 1 auto;
  }
}

.app-shell :deep(button:not(.el-button)) {
  min-height: 30px;
  padding: 0 12px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-border);
  border-radius: var(--console-radius-md);
  background: var(--console-panel-soft);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.app-shell :deep(button:not(.el-button, :disabled):hover) {
  color: var(--console-text-primary);
  border-color: var(--console-primary-hover);
  background: rgba(59, 130, 246, 0.14);
}

.app-shell :deep(button:not(.el-button).primary) {
  color: #fff;
  border-color: var(--console-primary);
  background: var(--console-primary);
}

.app-shell :deep(button:not(.el-button).danger) {
  color: #fecaca;
  border-color: rgba(239, 68, 68, 0.5);
  background: rgba(239, 68, 68, 0.12);
}

.app-shell :deep(button:not(.el-button):disabled) {
  color: var(--console-input-text-disabled);
  border-color: var(--console-input-border-disabled);
  background: var(--console-input-bg-disabled);
  cursor: not-allowed;
  opacity: 1;
}

.app-shell :deep(input:not(.el-input__inner)),
.app-shell :deep(select),
.app-shell :deep(textarea:not(.el-textarea__inner)) {
  min-height: 30px;
  padding: 0 10px;
  color: var(--console-text-secondary);
  border: 1px solid var(--console-input-border);
  border-radius: var(--console-radius-md);
  outline: none;
  background: var(--console-input-bg);
  font: inherit;
  font-size: 12px;
}

.app-shell :deep(textarea:not(.el-textarea__inner)) {
  height: auto;
  padding: 10px;
  resize: vertical;
}

.app-shell :deep(input:not(.el-input__inner):focus),
.app-shell :deep(select:focus),
.app-shell :deep(textarea:not(.el-textarea__inner):focus) {
  border-color: var(--console-primary-hover);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.16);
}

@media (width <= 900px) {
  .app-shell :deep(button[data-navigation-trigger]),
  .app-shell__drawer-heading button {
    min-height: 44px;
    padding: 0;
    font-size: 20px;
  }
}
</style>
