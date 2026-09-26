// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const INVENTORY = {
  services: [{
    name: 'ledger-service',
    port: 8101,
    status: 'UP',
    reachable: true,
    latencyMs: 12,
    version: '1.0.0',
    gitCommit: 'abcdef1',
    stack: { quarkus: { version: '3.27.0' } },
  }],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('preserves verified inventory evidence through collector failure and retry', async ({ page }) => {
  let inventoryRequest = 0
  await page.route('**/api/services/health', route => {
    inventoryRequest += 1
    if (inventoryRequest === 2) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(INVENTORY) })
  })
  await page.route('**/api/sbom/cve?**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"vulns":[]}',
  }))

  await page.goto('/system/inventory')
  await expect(page.getByText('3.27.0')).toBeVisible()
  await expect(page.getByText(/No known CVE|Žádné známé CVE/)).toBeVisible()

  await page.getByRole('button', { name: /Refresh service inventory|Obnovit inventář služeb/ }).click()
  await expect(page.getByText(/Showing the last verified inventory|Zobrazuji poslední ověřený inventář/)).toBeVisible()
  await expect(page.getByText('3.27.0')).toBeVisible()

  await page.getByRole('button', { name: /Retry|Zkusit znovu/ }).click()
  await expect(page.getByText(/Showing the last verified inventory|Zobrazuji poslední ověřený inventář/)).toBeHidden()
  await expect(page.getByText('3.27.0')).toBeVisible()
  expect(inventoryRequest).toBe(3)
})
