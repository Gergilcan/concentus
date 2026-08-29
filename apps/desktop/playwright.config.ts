import { defineConfig } from '@playwright/test'

/**
 * The shell's smoke test: Playwright launches the real Electron main process (dist/main.js, so
 * `pnpm build` first — the test:e2e script does) and looks at the windows it opens. One worker,
 * because each launch is a whole application with a tray icon and a single-instance lock.
 */
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 90_000,
  reporter: 'list',
  use: {
    trace: 'retain-on-failure',
  },
})
