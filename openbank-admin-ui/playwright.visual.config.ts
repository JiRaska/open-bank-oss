// SPDX-License-Identifier: Apache-2.0
// Visual regression lane (ADR-0359). Committed Playwright baselines, rendered ONLY inside the
// pinned Playwright container image (scripts/visual.sh) so the pixels a developer commits are
// the pixels CI compares. Never run this config on a bare host: a macOS or a different Linux
// font stack renders different glyphs, and the lane would report that as a regression.

import { defineConfig } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const port = process.env.OPENBANK_VISUAL_PORT ?? '3002'
const baseURL = `http://localhost:${port}`
const prebuilt = process.env.OPENBANK_E2E_PREBUILT === 'true'

export default defineConfig({
  testDir: './e2e-visual',
  // One baseline per page/theme. No {platform} suffix on purpose: there is exactly one
  // rendering platform, the container, and a second suffix would hide that assumption.
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  timeout: 60_000,
  // A retry would turn a nondeterministic screenshot into a green one. Flake must be visible.
  retries: 0,
  // Never write a baseline implicitly. Playwright's default ('missing') would record the first
  // render of a new or deleted baseline and pass, so the lane would compare nothing. A baseline
  // is created only by `scripts/visual.sh --update` (which passes --update-snapshots=all).
  updateSnapshots: 'none',
  workers: 2,
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  outputDir: 'build/visual-results/artifacts',
  expect: {
    toHaveScreenshot: {
      // Same image + same Chromium + frozen data and clock: the render is byte-stable, so
      // any differing pixel is a change. Antialiasing tolerance stays at the default 0.2.
      maxDiffPixels: 0,
      animations: 'disabled',
      caret: 'hide',
      scale: 'css',
    },
  },
  // The JUnit file lives OUTSIDE build/test-results: collect-test-run-evidence.py globs that
  // tree for suites, and a visual case must not be counted as e2e evidence too.
  reporter: [
    ['list'],
    ['junit', { outputFile: 'build/visual-results/visual.xml' }],
    ['html', { open: 'never', outputFolder: 'build/visual-results/report' }],
  ],
  use: {
    baseURL,
    browserName: 'chromium',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    locale: 'en-US',
    timezoneId: 'UTC',
    reducedMotion: 'reduce',
  },
  projects: [
    { name: 'light', use: { colorScheme: 'light' } },
    { name: 'dark', use: { colorScheme: 'dark' } },
  ],
  webServer: {
    command: `${prebuilt ? '' : 'npm run build && '}npm run start -- -p ${port}`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: prebuilt ? 120_000 : 300_000,
    env: {
      OPENBANK_REPO_ROOT: '../',
      NEXTAUTH_URL: baseURL,
      NEXTAUTH_SECRET: process.env.NEXTAUTH_SECRET ?? 'e2e-test-secret',
      KEYCLOAK_PUBLIC_URL: 'https://keycloak.e2e.invalid',
      KEYCLOAK_CLIENT_SECRET: process.env.KEYCLOAK_CLIENT_SECRET ?? randomUUID(),
      ALLOW_INSECURE_STUDIO_URLS: 'true',
      NEXT_TELEMETRY_DISABLED: '1',
    },
  },
})
