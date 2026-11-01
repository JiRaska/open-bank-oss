// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Config for the AUTHENTICATED deployed lane (#13171): specs tagged @sandbox-session sign in
// through the real Keycloak login form as the least-privilege e2e operator and smoke-test each
// money-path screen read-only. Kept apart from playwright.deployed.config.ts (no browser, no
// credentials) and from playwright.config.ts (stubbed localhost server). The tag deliberately does
// not contain "@deployed", so the credential-free auth-gate lane never picks these specs up.
import { defineConfig } from '@playwright/test'

export const SESSION_STATE = 'build/deployed-session/storage-state.json'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  retries: 1,
  workers: 2,
  reporter: [
    ['github'],
    ['junit', {
      outputFile: process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/deployed-session/deployed-session.xml',
      includeRetries: true,
    }],
  ],
  use: {
    baseURL: process.env.ADMIN_UI_DEPLOYED_URL ?? 'https://admin.open-bank.tech',
    trace: 'off',        // a trace would capture the typed password and session cookies
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    { name: 'sign-in', testMatch: /deployed-session\.setup\.ts/ },
    {
      name: 'smoke',
      testMatch: /deployed-session\.spec\.ts/,
      grep: /@sandbox-session/,
      dependencies: ['sign-in'],
      use: { storageState: SESSION_STATE },
    },
  ],
})
