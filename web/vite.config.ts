/// <reference types="vitest/config" />
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The dashboard is served by the Spring server from the same origin, so the
// session cookie and CSRF token work without CORS. In dev, Vite proxies the API, so the
// cookies stay on localhost:5173; ping URLs built by the server point at :8080.
export default defineConfig({
  plugins: [tanstackRouter({ target: "react", autoCodeSplitting: true }), react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "src") } },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/ping": "http://localhost:8080",
    },
  },
  build: { outDir: "dist" },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
