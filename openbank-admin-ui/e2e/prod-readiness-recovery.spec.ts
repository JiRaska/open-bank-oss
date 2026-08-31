// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

const REPORT = {
  generated_for: '2026-08-31',
  dimensions: [{ code: 'C1', name: 'Code' }],
  services: [{
    service: 'ledger',
    money_path: true,
    scores: { C1: 3 },
    evidence: { C1: 'verified' },
    gate: 'GO',
  }],
}

test('preserves the last verified readiness report through an outage and retry', async ({ page }) => {
  let request = 0
  let failNextRequest = false
  await page.route('**/api/prod-readiness', route => {
    request += 1
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(REPORT) })
  })

  await page.goto('/system/readiness')
  await expect(page.getByText('ledger', { exact: true })).toBeVisible()
  await expect(page.getByText('GO', { exact: true }).first()).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit připravenost na produkci|Refresh production readiness/ }).click()

  const stale = page.getByRole('status')
  await expect(stale).toContainText(/zobrazuji poslední dostupný report|showing the last available report/)
  await expect(page.getByText('ledger', { exact: true })).toBeVisible()
  await expect(page.getByText('GO', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: /Zkusit znovu načíst report připravenosti|Retry loading the readiness report/ }).click()
  await expect(stale).toBeHidden()
  await expect(page.getByText('ledger', { exact: true })).toBeVisible()
  expect(request).toBeGreaterThanOrEqual(3)
})
