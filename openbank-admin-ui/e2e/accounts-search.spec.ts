// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

const account = (id: string, accountNumber: string) => ({
  id,
  accountNumber,
  accountType: 'CURRENT',
  currencyCode: 'CZK',
  status: 'ACTIVE',
  partyId: '11111111-1111-1111-1111-111111111111',
  openedAt: '2026-08-01T00:00:00Z',
})

test('never lets a superseded account search overwrite the current query', async ({ page }) => {
  let requests = 0
  await page.route('**/api/svc/account-service/api/v1/accounts/search?**', async route => {
    requests += 1
    const query = new URL(route.request().url()).searchParams.get('q')
    if (query === 'OLD') {
      await new Promise(resolve => setTimeout(resolve, 800))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [account('old-account', 'OLD-ACCOUNT')], pagination: { limit: 25, hasNextPage: false } }),
      }).catch(() => { /* the replacement query deliberately aborts this request */ })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [account('new-account', 'NEW-ACCOUNT')], pagination: { limit: 25, hasNextPage: false } }),
    })
  })

  await page.goto('/accounts')
  const input = page.locator('#accounts-query')
  const search = page.getByRole('button', { name: /Vyhledat účty|Search accounts/ })

  await input.fill('old')
  await search.click()
  await expect(search).toHaveAttribute('aria-busy', 'true')

  await input.fill('new')
  await expect(page.getByText(/Vyberte party|Select a party/)).toBeVisible()
  await search.click()
  await expect(page.getByText('NEW-ACCOUNT', { exact: true })).toBeVisible()
  await expect(input).toHaveValue('new')

  await page.waitForTimeout(1000)
  await expect(page.getByText('OLD-ACCOUNT', { exact: true })).toHaveCount(0)
  await expect(page.getByText('NEW-ACCOUNT', { exact: true })).toBeVisible()
  expect(requests).toBe(2)
})

test('rejects a malformed IBAN locally without calling account-service', async ({ page }) => {
  let requests = 0
  await page.route('**/api/svc/account-service/**', async route => {
    requests += 1
    await route.fulfill({ status: 500, body: '' })
  })

  await page.goto('/accounts')
  const input = page.locator('#accounts-query')
  await input.fill('CZ6508000000192000145398')
  await page.getByRole('button', { name: /Vyhledat účty|Search accounts/ }).click()

  await expect(input).toHaveAttribute('aria-invalid', 'true')
  await expect(page.locator('#accounts-query-error')).toContainText(/kontrolní číslice|check digits/i)
  expect(requests).toBe(0)
})
