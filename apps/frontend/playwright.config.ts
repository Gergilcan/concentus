import { defineConfig, devices } from '@playwright/test'

/**
 * UI tests against the real application: the packaged jar serving the built frontend, a real
 * embedded PostgreSQL, the real /api — started by e2e/server.mjs on a scratch data directory.
 *
 * One worker, on purpose. The specs share a single backend and database, and several of them
 * create, rename and delete flows; parallel workers would race each other's state for no gain —
 * the whole suite is a couple of minutes of mostly-idle page waits.
 */
const PORT = Number(process.env.E2E_PORT ?? 8799)

export default defineConfig({
  testDir: './e2e',
  globalTeardown: './e2e/teardown.ts',
  workers: 1,
  // Deterministic order: specs are numbered, and later ones build on earlier ones' state (the
  // CRUD spec leaves flows the studio spec reuses). Alphabetical is the documented file order.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'node e2e/server.mjs',
    url: `http://127.0.0.1:${PORT}/actuator/health/readiness`,
    // A cold start pays initdb and the library seeding on top of the JVM — far beyond the default.
    timeout: 180_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})
