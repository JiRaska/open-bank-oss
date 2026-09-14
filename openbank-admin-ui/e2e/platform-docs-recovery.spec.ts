// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('cluster dossier degrades safely when topology is unavailable', async ({ page }) => {
  await page.route('**/api/cluster/topology', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'unavailable' }),
  }))
  await page.goto('/docs/cluster')
  await expect(page.getByRole('status')).toContainText(/Topologie clusteru není dostupná|Cluster topology is unavailable/)
  await expect(page.getByRole('button', { name: /Obnovit|Refresh/ })).toBeEnabled()
})

test('test intelligence degrades safely when its report is unavailable', async ({ page }) => {
  await page.route('**/api/test-intelligence', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'unavailable' }),
  }))
  await page.goto('/system/tests')
  await expect(page.getByText(/Report není dostupný|Report is unavailable/)).toBeVisible()
  await expect(page.getByRole('button', { name: /Obnovit systémové testy|Refresh system tests/ })).toBeEnabled()
})
