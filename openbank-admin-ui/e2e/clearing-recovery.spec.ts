import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const batch = {
  id: 'batch-2026-0042',
  batchReference: 'CLR-2026-0042',
  rail: 'SEPA_SCT',
  settlementType: 'NET',
  status: 'IN_CLEARING',
  itemCount: 18,
  totalDebit: 125000.50,
  totalCredit: 100000,
  netPosition: -25000.50,
  currency: 'EUR',
  cycleId: 'cycle-42',
  settlementDate: '2026-08-31',
  settledAt: null,
  createdAt: '2026-08-31T12:00:00Z',
  updatedAt: '2026-08-31T12:05:00Z',
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
  await expect(page.getByRole('table').getByText(/EUR\s*125,000\.50/)).toBeVisible()
  await expect(page.getByText('IN_CLEARING', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit clearing dávky|Refresh clearing batches/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('CLR-2026-0042')).toBeVisible()
  await expect(page.getByRole('table').getByText(/EUR\s*125,000\.50/)).toBeVisible()
  await expect(page.getByText('IN_CLEARING', { exact: true })).toBeVisible()
  await expect(page.getByText(/zatím žádné clearing dávky|no clearing batches yet/)).toHaveCount(0)
})
