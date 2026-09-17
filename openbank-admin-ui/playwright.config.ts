// SPDX-License-Identifier: Apache-2.0
// ADR-0076 Layer 2 — Playwright E2E configuration
//
// Runs against the Next.js dev server by default, or a prebuilt production server when opted in.
// Tests live in e2e/ and mock BFF endpoints via page.route() — no live services needed.
// Scoped to pages that render live service state (docs coverage, health, governance).

import { defineConfig, devices } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const e2ePort = process.env.OPENBANK_E2E_PORT ?? '3001'
const e2eBaseUrl = `http://localhost:${e2ePort}`
const useProductionServer = process.env.OPENBANK_E2E_SERVER === 'production'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // Fail fast in CI — one retry on flake
  retries: process.env.CI ? 1 : 0,
  // The suite has grown past 200 browser tests. One CI worker made its wall time grow
  // linearly until otherwise-green PR runs were cancelled near the end of the suite.
  // Files are isolated (fresh browser context plus route-local mocks), while tests inside
  // each file keep Playwright's default serial ordering, so four workers bound wall time
  // without weakening state isolation or retry evidence.
  workers: process.env.CI ? 4 : undefined,
  // CI retains both the human GitHub/HTML reports and a machine-readable JUnit
  // report. The latter is consumed by the shared Test Intelligence envelope;
  // merely exporting PLAYWRIGHT_JUNIT_OUTPUT_FILE in the workflow does nothing
  // unless the reporter is configured to write it.
  reporter: process.env.CI ? [
    ['github'],
    ['html', { open: 'never' }],
    ['junit', {
      outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/playwright.xml',
      // Preserve failed attempts when a retry passes. The collector retains only bounded
      // counts/durations from these entries; suite and testcase verdicts remain passed.
      includeRetries: true,
    }],
  ] : 'list',

  use: {
    baseURL: e2eBaseUrl,
    // Don't re-use browser state between tests — each spec gets a fresh page
    trace: 'on-first-retry',
    // Contrast and layout assertions should observe the settled accessible state,
    // not an intermediate frame of the shell's opacity/position entrance.
    reducedMotion: 'reduce',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    // The caller builds first; a production run avoids on-demand compilation and
    // the unbounded dev cache during broad browser sweeps.
    command: useProductionServer
      ? `npm run start -- -p ${e2ePort}`
      : `npm run dev -- -p ${e2ePort}`,
    url: e2eBaseUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      // Point docs bundle to the repo root so libs docs are found
      OPENBANK_REPO_ROOT: '../',
      // Disable auth for E2E tests. e2e/helpers/auth.ts mints session cookies with this
      // same secret (falls back to the same default) — keep the two in sync.
      NEXTAUTH_URL: e2eBaseUrl,
      NEXTAUTH_SECRET: process.env.NEXTAUTH_SECRET ?? 'e2e-test-secret',
      ...(useProductionServer ? {
        KEYCLOAK_PUBLIC_URL: 'https://keycloak.e2e.invalid',
        KEYCLOAK_CLIENT_SECRET: process.env.KEYCLOAK_CLIENT_SECRET ?? randomUUID(),
        ALLOW_INSECURE_STUDIO_URLS: 'true',
      } : {}),
    },
  },
})
