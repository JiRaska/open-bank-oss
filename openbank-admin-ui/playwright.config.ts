// SPDX-License-Identifier: MPL-2.0
// ADR-0076 Layer 2 — Playwright E2E configuration
//
// Runs against the Next.js dev server (auto-started before tests, torn down after).
// Tests live in e2e/ and mock BFF endpoints via page.route() — no live services needed.
// Scoped to pages that render live service state (docs coverage, health, governance).

import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // Fail fast in CI — one retry on flake
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',

  use: {
    baseURL: 'http://localhost:3001',
    // Don't re-use browser state between tests — each spec gets a fresh page
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    command: 'npm run dev -- -p 3001',
    url: 'http://localhost:3001',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      // Point docs bundle to the repo root so libs docs are found
      OPENBANK_REPO_ROOT: '../',
      // Disable auth for E2E tests
      NEXTAUTH_URL: 'http://localhost:3001',
      NEXTAUTH_SECRET: 'e2e-test-secret',
    },
  },
})
