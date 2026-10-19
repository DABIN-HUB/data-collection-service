<template>
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path :d="paths[name] || paths.info" />
  </svg>
</template>

<script setup lang="ts">
defineProps<{ name: string }>();

const paths: Record<string, string> = {
  search: "M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0ZM16 16l4.5 4.5",
  refresh: "M20 7v5h-5M4 17v-5h5M18.5 7A7.5 7.5 0 0 0 5 6M5.5 17A7.5 7.5 0 0 0 19 18",
  download: "M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5",
  upload: "M12 15V3m-4 4 4-4 4 4M4 16v5h16v-5",
  plus: "M12 5v14M5 12h14",
  more: "M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0Zm7 0a1 1 0 1 1-2 0 1 1 0 0 1 2 0Zm7 0a1 1 0 1 1-2 0 1 1 0 0 1 2 0Z",
  local: "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM8 21h8m-4-4v4",
  cloud: "M6 18h12a4 4 0 0 0 1-8 7 7 0 0 0-13-2 5 5 0 0 0 0 10Z",
  info: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 11v6m0-10v.1",
  alert: "m12 3 10 18H2L12 3Zm0 6v5m0 3v.1",
  box: "m12 3 9 5v9l-9 5-9-5V8l9-5Zm-9 5 9 5 9-5M12 13v9m-5-17 9 5",
  play: "m8 5 11 7-11 7V5Z",
  stop: "M6 5h12a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z",
  chart: "M3 3v18h18M6 16l4-6 4 3 5-8",
  diff: "M8 3v18m8-18v18M3 7h10m-4-4 4 4-4 4m12 6H11m4-4-4 4 4 4",
  control: "M5 3v18m7-18v18m7-18v18M2 8h6m1 8h6m1-9h6",
  shadow: "M5 4h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM8 21h13V8M7 8h4m-4 4h4",
  trash: "M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7"
};
</script>

<style scoped>
svg {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
