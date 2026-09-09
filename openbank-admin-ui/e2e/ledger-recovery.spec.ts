import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const journalPage = {
  data: [{
    id: 'entry-0042',
    entryNumber: 42,
    transactionId: 'transaction-12345678',
    entryDate: '2026-08-31',
    valueDate: '2026-08-31',
    description: 'Customer transfer settlement',
    status: 'POSTED',
    createdAt: '2026-08-31T12:00:00Z',
    lines: [
      { id: 'line-debit', glAccountId: 'gl-debit-12345678', side: 'DEBIT', amount: 1250, currencyCode: 'EUR', baseAmount: 1250, baseCurrencyCode: 'EUR', sequence: 1 },
      { id: 'line-credit', glAccountId: 'gl-credit-1234567', side: 'CREDIT', amount: 1250, currencyCode: 'EUR', baseAmount: 1250, baseCurrencyCode: 'EUR', sequence: 2 },
    ],
  }],
  pagination: { limit: 20, hasNextPage: false, nextCursor: null },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps balanced journal evidence visible after a repeated search fails', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/ledger-service/api/v1/journals**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(journalPage) }))

  await page.goto('/ledger')
  await page.getByRole('button', { name: /Načíst záznamy|Load Entries/ }).click()

  await expect(page.getByText('Customer transfer settlement')).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: /Zobrazit řádky deníku|Show journal lines/ }).click()
  await expect(page.getByText('DEBIT', { exact: true })).toBeVisible()
  await expect(page.getByText('CREDIT', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Načíst záznamy|Load Entries/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný výsledek|Showing the last successful result/)).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('Customer transfer settlement')).toBeVisible()
  await expect(page.getByText('DEBIT', { exact: true })).toBeVisible()
  await expect(page.getByText('CREDIT', { exact: true })).toBeVisible()
})
