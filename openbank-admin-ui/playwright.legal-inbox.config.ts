// SPDX-License-Identifier: Apache-2.0

import { defineConfig, devices } from '@playwright/test'

const port = process.env.OPENBANK_E2E_PORT ?? '39192'
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  testMatch: 'legal-inbox-postgres.spec.ts',
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  use: { ...devices['Desktop Chrome'], baseURL },
  webServer: {
    command: `npm run predev && node node_modules/next/dist/bin/next dev --hostname 127.0.0.1 --port ${port}`,
    url: baseURL + '/auth/login',
    stdout: 'pipe',
    timeout: 240_000,
    env: {
      OPENBANK_REPO_ROOT: '../',
      NEXTAUTH_URL: baseURL,
      NEXTAUTH_SECRET: process.env.NEXTAUTH_SECRET ?? 'e2e-test-secret',
    },
  },
})
