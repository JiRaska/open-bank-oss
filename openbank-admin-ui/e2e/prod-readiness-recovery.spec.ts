// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

const REPORT = {
  schema_version: 1,
  generated_for: '2026-08-31',
  collected_at: '2026-08-31T13:00:00Z',
  source_sha: 'a'.repeat(40),
  scope: { kind: 'service-module-matrix-v1', service_count: 1, money_path_count: 1, roster_sha256: 'b'.repeat(64) },
  dimensions: ['C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'C8', 'C9'].map(code => ({ code, name: code })),
  services: [{
    service: 'ledger',
    money_path: true,
    scores: { C1: 3, C2: 2, C3: 2, C4: 2, C5: 3, C6: 2, C7: 3, C8: 2, C9: 2 },
    evidence: { C1: 'verified', C2: 'verified', C3: 'verified', C4: 'verified', C5: 'verified', C6: 'verified', C7: 'verified', C8: 'verified', C9: 'verified' },
    gate: 'GO',
  }],
}

const API_RESPONSE = { status: 'ready', source: 'runtime', report: REPORT, provenance: null }

test('preserves the last verified readiness report through an outage and retry', async ({ page }) => {
  let request = 0
  let failNextRequest = false
  await page.route('**/api/prod-readiness', route => {
    request += 1
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(API_RESPONSE) })
  })

  await page.goto('/system/readiness')
  const ledgerRow = page.getByRole('row').filter({
    has: page.getByText('ledger', { exact: true }),
  })
  const ledgerGate = ledgerRow.getByText('GO', { exact: true })
  await expect(ledgerRow).toBeVisible()
  await expect(ledgerGate).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit připravenost na produkci|Refresh production readiness/ }).click()

  const stale = page.getByRole('status')
  await expect(stale).toContainText(/zobrazuji poslední dostupný report|showing the last available report/)
  await expect(ledgerRow).toBeVisible()
  await expect(ledgerGate).toBeVisible()

  await page.getByRole('button', { name: /Zkusit znovu načíst report připravenosti|Retry loading the readiness report/ }).click()
  await expect(stale).toBeHidden()
  await expect(ledgerRow).toBeVisible()
  await expect(ledgerGate).toBeVisible()
  expect(request).toBeGreaterThanOrEqual(3)
})
