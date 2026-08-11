import { defineConfig, devices } from '@playwright/test'

/**
 * UI tests against the real application: the packaged jar serving the built frontend, a real
 * embedded PostgreSQL, the real /api. There is no shared webServer here — EACH WORKER starts its
 * own backend on its own port with its own database (see fixtures.ts), which is what lets the
 * suite run parallel without racing: isolation by construction, not by discipline.
 *
 * Specs that chain state (the CRUD scenario) stay serial within their file; independent specs
 * spread across workers freely.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: true,
  // Each worker costs a JVM + initdb (~10s, once per worker). Four saturate a CI runner nicely;
  // two keep a dev machine responsive while still exercising the parallel paths locally.
  workers: process.env.CI ? 4 : 2,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
