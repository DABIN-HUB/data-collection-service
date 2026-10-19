<template>
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path :d="paths[name] || paths.device" /></svg>
</template>

<script setup lang="ts">
defineProps<{ name: string }>();

const paths: Record<string, string> = {
  device: "M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM8 7h8M8 11h8M8 15h3M8 18h3M17 17h.01",
  setup: "M8 3v5M16 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4",
  points: "M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3zM15 15h6v6h-6z",
  alarm: "M4 17h16l-2-3V9a6 6 0 0 0-12 0v5zM10 21h4",
  cloud: "M6 18a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5 5 0 0 1 0 11zM9 12l3-3 3 3M12 9v7",
  json: "M8 3H6v6l-2 3 2 3v6h2M16 3h2v6l2 3-2 3v6h-2M10 9l-1 3 1 3M14 9l1 3-1 3",
  close: "M6 6l12 12M6 18 18 6"
};
</script>

<style scoped>
svg {
  width: 18px;
  height: 18px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
