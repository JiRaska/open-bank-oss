// SPDX-License-Identifier: Apache-2.0
import { defineConfig, devices } from '@playwright/test'

// The caller starts the actual image by digest. This config must never build or
// start a second Next.js server and accidentally test a different artifact.
const baseURL = process.env.OPENBANK_IMAGE_TEST_URL
if (!baseURL || !/^http:\/\/127\.0\.0\.1:\d+$/.test(baseURL)) {
  throw new Error('OPENBANK_IMAGE_TEST_URL must point to the ephemeral loopback container')
}
export default defineConfig({
  testDir: './e2e', testMatch: 'image-delivery.spec.ts',
  timeout: 30_000, retries: 0, workers: 1,
  reporter: [['list'], ['json', { outputFile: 'test-results/image-delivery.json' }]],
  use: { ...devices['Desktop Chrome'], baseURL, trace: 'retain-on-failure' },
})
