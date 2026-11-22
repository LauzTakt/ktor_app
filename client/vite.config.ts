import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The passenger voice assistant (ex sbb-client), served by ktor_app at /assistant/.
// Production: `npm run build` writes ../build/client/static/assistant/, which Gradle adds to the jar's resources.
// Dev (`npm run dev`, hot reload on :5190): every server call goes to ktor_app (KTOR_URL, default :8080), which
// owns them all: /api/v1 (assistant, situation, arrivals, geometry, trains), /api/brief*, /api/voice/session,
// /api/fleet, /api/nearest, /ws/fleet, the static rail network /data/*.geojson and the shared /theme.css and /tikt.css (the mascot). Nothing talks to sbb-twin's HTTP API.
const ktor = process.env.KTOR_URL ?? "http://127.0.0.1:8080";
const proxy = {
  "/api": { target: ktor, changeOrigin: true },
  "/data": { target: ktor, changeOrigin: true },
  "/trip-watch": { target: ktor, changeOrigin: true },
  "/passenger.css": { target: ktor, changeOrigin: true },
  "/theme.css": { target: ktor, changeOrigin: true },
  "/vendor/daisyui.css": { target: ktor, changeOrigin: true },
  "/tikt.css": { target: ktor, changeOrigin: true },
  "/ws": { target: ktor.replace(/^http/, "ws"), ws: true, changeOrigin: true },
};

export default defineConfig({
  base: "/assistant/",
  plugins: [react()],
  server: { host: "127.0.0.1", port: 5190, strictPort: true, allowedHosts: [".trycloudflare.com"], proxy },
  optimizeDeps: { exclude: ["maplibre-gl"] },
  build: { outDir: "../build/client/static/assistant", emptyOutDir: true, chunkSizeWarningLimit: 2500 },
});
