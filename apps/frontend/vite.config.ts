/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The frontend is not deployed anywhere. It is built into `dist/`, copied into the backend jar as
 * static resources, and served by the backend itself — which is what puts the page, `/api` and
 * `/ws` on one origin and removed the proxy, the CORS grant and the nginx container along with it.
 *
 * The dev server still needs to reach a backend, and does so with a proxy for the same reason:
 * keeping requests same-origin means the session and XSRF cookies behave in development exactly as
 * they do in the packaged app, rather than needing SameSite=None and a CORS allowance that only
 * exist in development.
 *
 * 8734 is the port the desktop shell prefers, so `pnpm dev` talks to the backend the app itself
 * would start. Running the backend by hand on 8080 instead means overriding the target here.
 */
const BACKEND_URL = 'http://127.0.0.1:8734'

export default defineConfig(() => {
  const proxy = {
    '/api': { target: BACKEND_URL, changeOrigin: true },
    '/ws': { target: BACKEND_URL.replace(/^http/, 'ws'), ws: true, changeOrigin: true },
  }

  return {
    plugins: [react()],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      globals: true,
    },
  }
})
