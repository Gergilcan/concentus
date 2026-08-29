import { defineConfig } from 'vitest/config'

/**
 * The shell's unit tests run under plain node: `electron` is mocked in every suite, and so are
 * the child processes, the keyring and the data directory. Nothing here needs a display or a
 * backend — that is what e2e/ (Playwright) is for, and why it is excluded.
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    exclude: ['**/node_modules/**', 'dist/**', 'e2e/**'],
    // Call histories are per test; implementations set in a beforeEach stay.
    clearMocks: true,
  },
})
