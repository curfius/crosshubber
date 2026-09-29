import { defineConfig, devices } from '@playwright/test';

const PORTAL_URL = process.env.E2E_PORTAL_URL ?? 'http://localhost:28094';

/**
 * Crosshubber portal E2E (plan/TEST_PLAN.md).
 *
 * The stack (docker compose project `crosshubber-e2e`, tenant `e2e`) is booted by the
 * webServer command and torn down by globalTeardown — unless it was already running
 * before the suite started (marker file `.stack-owned` decides ownership) or
 * E2E_KEEP_STACK=1 keeps it alive for debugging.
 *
 * Auth: the `setup` project logs both realm users in through the real Keycloak form and
 * saves storageState files; `admin` and `limited` projects reuse them.
 */
export default defineConfig({
  testDir: './specs',
  outputDir: './test-results',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalTeardown: './specs/teardown.global.ts',
  use: {
    baseURL: PORTAL_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /setup\/auth\.setup\.ts/,
    },
    {
      name: 'admin',
      testMatch: /specs\/.*\.spec\.ts/,
      testIgnore: /setup\/|limited-user/,
      use: {
        ...devices['Desktop Chrome'],
        locale: 'en-GB',
        storageState: 'storageState-admin.json',
      },
      dependencies: ['setup'],
    },
    {
      name: 'limited',
      testMatch: /limited-user\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        locale: 'en-GB',
        storageState: 'storageState-limited.json',
      },
      dependencies: ['setup'],
    },
  ],
  webServer: {
    command: 'node scripts/stack.mjs up',
    url: `${PORTAL_URL}/healthz`,
    reuseExistingServer: true,
    timeout: 480_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
