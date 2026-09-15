// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('keeps a wide banking table discoverable and keyboard-scrollable on mobile', async ({ page, context, baseURL }) => {
  await page.setViewportSize({ width: 320, height: 760 })
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/svc/account-service/api/v1/accounts/search?**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      data: [{
        id: 'mobile-account', accountNumber: '1234567890123456', accountType: 'CURRENT',
        currencyCode: 'CZK', status: 'ACTIVE', partyId: '11111111-1111-4111-8111-111111111111',
        openedAt: '2026-09-15T00:00:00Z',
      }],
      pagination: { limit: 25, hasNextPage: false },
    }),
  }))

  await page.goto('/accounts')
  await page.locator('#accounts-query').fill('1234')
  await page.getByRole('button', { name: 'Search accounts' }).click()
  await expect(page.getByText('1234567890123456', { exact: true })).toBeVisible()
  await expect(page.getByText('Scroll horizontally to see every column.')).toBeVisible()

  const viewport = page.getByRole('region', { name: 'Scrollable accounts table' })
  await expect(viewport).toHaveAttribute('tabindex', '0')
  expect(await viewport.evaluate(element => element.scrollWidth)).toBeGreaterThan(await viewport.evaluate(element => element.clientWidth))
  await viewport.focus()
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => viewport.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
})
