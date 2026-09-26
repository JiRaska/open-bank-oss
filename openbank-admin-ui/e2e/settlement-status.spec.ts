// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { LANG_STORAGE_KEY } from '../src/lib/i18n/language'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const detail = {
  id, payerAccountId: 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876', payeeAccountId: 'ab72c875-9e17-4491-a5ef-0bdb34946a3b',
  amount: '999999999999999.9900', currency: 'CZK', status: 'BALANCE_STATE_UNKNOWN',
  createdAt: '2026-09-26T10:00:00Z', updatedAt: '2026-09-26T10:01:00Z',
}

test('operator looks up exact financial state on mobile and refreshes without a write', async ({ page, context, baseURL }, testInfo) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(key => localStorage.setItem(key, 'en'), LANG_STORAGE_KEY)
  let reads = 0
  await page.route(`**/api/settlements/${id}`, async route => {
    expect(route.request().method()).toBe('GET')
    expect(route.request().postData()).toBeNull()
    reads += 1
    await route.fulfill({ json: { ...detail, status: reads === 1 ? 'BALANCE_STATE_UNKNOWN' : 'BOOKED' } })
  })
  await page.goto('/settlements')
  await page.getByLabel(/Transfer ID|Identifikátor převodu/).fill(id)
  await page.getByRole('button', { name: /Show state|Zobrazit stav/ }).click()
  await expect(page).toHaveURL(new RegExp(`/settlements/${id}$`))
  const panel = page.getByRole('region', { name: /Persisted settlement state|Uložený stav settlementu/ })
  await expect(panel).toContainText('999999999999999.9900 CZK')
  expect(await panel.evaluate(element => parseFloat(getComputedStyle(element).paddingLeft))).toBeGreaterThanOrEqual(16)
  const refresh = panel.getByRole('button', { name: /Refresh state|Obnovit stav/ })
  expect((await refresh.boundingBox())!.height).toBeGreaterThanOrEqual(44)
  await expect(panel.getByRole('status')).toContainText(/uncertain|není znám/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('settlement-status-mobile.png'), fullPage: true })
  await panel.getByRole('button', { name: /Refresh state|Obnovit stav/ }).click()
  await expect(panel.getByRole('status')).toContainText(/transfer is booked|Převod je zaúčtovaný/)
  expect(reads).toBe(2)
})
