import { defineConfig } from '@playwright/test';

const channel = process.env.EKB_E2E_BROWSER_CHANNEL;

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'line',
  use: {
    baseURL: process.env.EKB_E2E_BASE_URL || 'http://127.0.0.1:18080',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    ...(channel ? { channel } : {}),
  },
});
