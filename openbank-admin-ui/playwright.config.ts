// SPDX-License-Identifier: Apache-2.0
// ADR-0076 Layer 2 — Playwright E2E configuration
//
// Runs against a dedicated Next.js dev server (auto-started before tests, torn down after).
// Tests live in e2e/ and mock BFF endpoints via page.route() — no live services needed.
// Scoped to pages that render live service state (docs coverage, health, governance).

import { defineConfig, devices } from '@playwright/test'

const e2ePort = process.env.OPENBANK_E2E_PORT ?? '3001'
const e2eBaseUrl = `http://localhost:${e2ePort}`

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // Fail fast in CI — one retry on flake
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  // CI retains both the human GitHub/HTML reports and a machine-readable JUnit
  // report. The latter is consumed by the shared Test Intelligence envelope;
  // merely exporting PLAYWRIGHT_JUNIT_OUTPUT_FILE in the workflow does nothing
  // unless the reporter is configured to write it.
  reporter: process.env.CI ? [
    ['github'],
    ['html', { open: 'never' }],
    ['junit', { outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/playwright.xml' }],
  ] : 'list',

  use: {
    baseURL: e2eBaseUrl,
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
    // Webpack is deliberate here: Turbopack rejects the safe shared-dependency
    // symlink used by isolated worktrees, while production builds retain their
    // configured bundler. E2E needs a deterministic server, not a bundler test.
    command: `npm run dev -- --webpack -p ${e2ePort}`,
    url: e2eBaseUrl,
    // A reachable port is not evidence that it serves admin-ui: locally a different
    // project can already own 3001 and make every assertion target the wrong app.
    // Fail fast instead; callers that need parallel runs choose OPENBANK_E2E_PORT.
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      // Point docs bundle to the repo root so libs docs are found
      OPENBANK_REPO_ROOT: '../',
      // Disable auth for E2E tests. e2e/helpers/auth.ts mints session cookies with this
      // same secret (falls back to the same default) — keep the two in sync.
      NEXTAUTH_URL: e2eBaseUrl,
      NEXTAUTH_SECRET: process.env.NEXTAUTH_SECRET ?? 'e2e-test-secret',
    },
  },
})
