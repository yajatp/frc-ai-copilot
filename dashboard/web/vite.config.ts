import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard is served by the Java process in production, so assets are emitted into
// `dist/` and picked up by StaticFiles. In development the Vite dev server proxies /api to
// that same Java process, which keeps the API loopback-only and avoids any CORS surface.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    // Robot telemetry never leaves the laptop; sourcemaps make pit-side debugging survivable.
    sourcemap: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:5800",
        changeOrigin: false,
      },
    },
  },
});
