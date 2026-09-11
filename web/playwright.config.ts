import { defineConfig } from '@playwright/test';

const channel = process.env.EKB_E2E_BROWSER_CHANNEL;
const fixtureUrl = 'http://127.0.0.1:4174';
const deploymentSpec = '**/public-release-smoke.spec.ts';

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['line'], ['json', { outputFile: 'test-results/smoke-report.json' }]],
  use: {
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    ...(channel ? { channel } : {}),
  },
  projects: [
    {
      name: 'fixtures',
      testIgnore: deploymentSpec,
      use: { baseURL: fixtureUrl },
    },
    {
      name: 'deployment',
      testMatch: deploymentSpec,
      use: { baseURL: process.env.EKB_E2E_BASE_URL || 'http://127.0.0.1:18080' },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4174 --strictPort',
    url: fixtureUrl,
    reuseExistingServer: !process.env.CI,
  },
});
