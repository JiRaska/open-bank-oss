// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const sample = { services: [{ name: 'ledger-service', port: 8101, status: 'UP', reachable: true, latencyMs: 12 }] }

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  for (const path of ['/api/services/config', '/api/services/governance', '/api/pyrra/summary', '/api/temporal/status', '/api/tempo/api/search**']) {
    await page.route(`**${path}`, route => route.fulfill({ status: 503, contentType: 'application/json', body: '{}' }))
  }
  await page.route('**/api/catalog/services', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ services: [] }),
  }))
})

test('collector failure has no fabricated fleet outage and retry recovers', async ({ page }, testInfo) => {
  let available = false
  await page.route('**/api/services/health', route => route.fulfill({
    status: available ? 200 : 503, contentType: 'application/json',
    body: JSON.stringify(available ? sample : {}),
  }))
  await page.goto('/system/health')
  const main = page.locator('main')
  await expect(main.getByText('Service health could not be verified')).toBeVisible()
  await expect(main.getByText('DOWN', { exact: true })).toHaveCount(0)
  await expect(main.getByText('Healthy', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('collector-unavailable.png'), fullPage: true })
  available = true
  await main.getByRole('button', { name: 'Refresh system health' }).click()
  await expect(main.getByText('UP', { exact: true })).toBeVisible()
  await expect(main.getByText('Service health could not be verified')).toHaveCount(0)
  await expect(main.getByText(/Some configuration or governance details are unavailable/)).toBeVisible()
})

test('refresh failure labels retained healthy observations as stale', async ({ page }) => {
  let available = true
  await page.route('**/api/services/health', route => route.fulfill({
    status: available ? 200 : 503, contentType: 'application/json',
    body: JSON.stringify(available ? sample : {}),
  }))
  await page.goto('/system/health')
  const main = page.locator('main')
  await expect(main.getByText('UP', { exact: true })).toBeVisible()
  const checkedAt = await main.getByText(/Last successful check:/).textContent()
  available = false
  await main.getByRole('button', { name: 'Refresh system health' }).click()
  await expect(main.getByText('Showing the last verified health snapshot')).toBeVisible()
  await expect(main.getByText('UP', { exact: true })).toBeVisible()
  await expect(main.getByText(/Last successful check:/)).toHaveText(checkedAt!)
})

test('a real outage remains visible with a readable narrow summary', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ services: [{ ...sample.services[0], status: 'DOWN', reachable: false }] }),
  }))
  await page.goto('/system/health')
  const main = page.locator('main')
  await expect(main.getByText('DOWN', { exact: true })).toBeVisible()
  await expect(main.getByText('Unreachable', { exact: true })).toBeVisible()
  await expect(main.getByText('Service health could not be verified')).toHaveCount(0)
  const summary = main.getByText('Unreachable', { exact: true })
  await summary.scrollIntoViewIfNeeded()
  const card = await summary.locator('../..').boundingBox()
  expect(card!.width).toBeGreaterThanOrEqual(160)
  await page.screenshot({ path: testInfo.outputPath('verified-outage-mobile.png'), fullPage: true })
})
