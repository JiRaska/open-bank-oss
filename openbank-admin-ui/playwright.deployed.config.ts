// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Config for the specs tagged @deployed: they target a DEPLOYED Admin UI (default: the sandbox) and
// start no local server. Kept apart from playwright.config.ts, whose baseURL is the stubbed localhost
// server the other 100+ specs are written against.
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  grep: /@deployed/,
  timeout: 30_000,
  retries: 1,
  workers: 2,
  reporter: [
    ['github'],
    ['junit', {
      outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/auth-gate/deployed-auth-gate.xml',
      includeRetries: true,
    }],
  ],
  use: { baseURL: process.env.ADMIN_UI_DEPLOYED_URL ?? 'https://admin.open-bank.tech' },
})
