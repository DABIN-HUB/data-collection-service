import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url))
    }
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true
  },
  build: {
    outDir: "dist/renderer",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules/echarts")) {
            return "vendor-echarts";
          }
          if (id.includes("node_modules/xlsx")) {
            return "vendor-xlsx";
          }
          if (id.includes("node_modules/element-plus") || id.includes("node_modules/@element-plus")) {
            return "vendor-element-plus";
          }
          if (id.includes("node_modules/vue") || id.includes("node_modules/pinia") || id.includes("node_modules/vue-router")) {
            return "vendor-vue";
          }
        }
      }
    }
  }
});
