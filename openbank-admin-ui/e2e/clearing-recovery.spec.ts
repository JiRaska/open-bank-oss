import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const batch = {
  id: 'batch-2026-0042',
  batchReference: 'CLR-2026-0042',
  paymentRail: 'SEPA',
  status: 'PROCESSING',
  itemCount: 18,
  totalAmount: 125000.50,
  currency: 'EUR',
  createdAt: '2026-08-31T12:00:00Z',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/auth/session', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      user: {
        name: 'E2E Payments Operator',
        email: 'e2e-payments@openbank.test',
        roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_PAYMENTS'],
        accessToken: 'e2e-fake-access-token',
      },
      expires: '2099-01-01T00:00:00.000Z',
    }),
  }))
})

test('keeps clearing and settlement evidence visible after a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/clearing-service/api/v1/clearing/batches**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([batch]) }))

  await page.goto('/clearing')

  await expect(page.getByText('CLR-2026-0042')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('125,000.50')).toBeVisible()
  await expect(page.getByText('PROCESSING', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit clearing dávky|Refresh clearing batches/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('CLR-2026-0042')).toBeVisible()
  await expect(page.getByText('125,000.50')).toBeVisible()
  await expect(page.getByText('PROCESSING', { exact: true })).toBeVisible()
  await expect(page.getByText(/zatím žádné clearing dávky|no clearing batches yet/)).toHaveCount(0)
})
