/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// This file runs in Node, but the package it belongs to is a browser bundle and carries no
// @types/node. Declaring the two globals used here keeps `tsc -b` honest without that dependency.
declare const process: { env: Record<string, string | undefined>; cwd(): string }
declare const console: { log(message: string): void }

// Env lives in the repo root .env, alongside every other deployment setting. Nothing read here
// reaches the browser: only VITE_-prefixed variables are ever inlined into the bundle, and the
// frontend's own env dir is untouched.
const REPO_ROOT = process.cwd() + '/../..'

const DEFAULT_BACKEND_URL = 'http://localhost:8080'

/**
 * Dev/preview servers proxy API + WebSocket to the backend, so the frontend keeps using
 * same-origin relative URLs (/api, /ws) whatever it is pointed at. Same-origin is the point: the
 * session and XSRF cookies are ordinary host cookies, and a browser talking straight to another
 * origin would need SameSite=None on both plus a CORS grant before it sent them at all.
 *
 * CONCENTUS_BACKEND_URL picks the target — unset for the backend you run locally, or a deployment
 * (e.g. https://concentus.onrender.com) to work against that one instead.
 */
export default defineConfig(({ mode }) => {
  // Prefix '' loads every key, and an inline CONCENTUS_BACKEND_URL=... wins over the .env file.
  const env = loadEnv(mode, REPO_ROOT, '')
  const configured = (env.CONCENTUS_BACKEND_URL ?? '').trim()
  // Trailing slashes would double up against the proxied path.
  const target = (configured || DEFAULT_BACKEND_URL).replace(/\/+$/, '')
  // ws:// for http://, wss:// for https:// — the socket follows the backend's scheme.
  const wsTarget = target.replace(/^http/, 'ws')

  // Worth a line: pointing at the wrong backend otherwise looks like a backend that lost its data.
  if (configured) console.log(`[concentus] proxying /api and /ws to ${target}`)

  const proxy = {
    // changeOrigin so the Host header — and the TLS SNI that follows it — name the target; a
    // hosted backend behind a shared router answers 404 without that.
    '/api': { target, changeOrigin: true },
    '/ws': { target: wsTarget, ws: true, changeOrigin: true },
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
