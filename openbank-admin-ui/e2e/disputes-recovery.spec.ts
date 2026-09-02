import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const dispute = {
  id: 'dispute-001',
  referenceNumber: 'DSP-2026-0042',
  disputeType: 'CARD_NOT_PRESENT',
  status: 'UNDER_REVIEW',
  claimantAccountId: 'account-123',
  transactionId: 'transaction-12345678',
  amount: 2450,
  currency: 'CZK',
  slaDeadline: '2026-08-01T00:00:00Z',
  createdAt: '2026-07-01T09:00:00Z',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/auth/session', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      user: {
        name: 'E2E Compliance',
        email: 'e2e-compliance@openbank.test',
        roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_COMPLIANCE'],
        accessToken: 'e2e-fake-access-token',
      },
      expires: '2099-01-01T00:00:00.000Z',
    }),
  }))
})

test('keeps dispute and SLA evidence visible after a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/dispute-service/api/v1/disputes**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([dispute]) }))

  await page.goto('/disputes')

  await expect(page.getByText('DSP-2026-0042')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/PORUŠENÍ SLA|SLA BREACH/).first()).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit seznam sporů|Refresh disputes/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('DSP-2026-0042')).toBeVisible()
  await expect(page.getByText(/PORUŠENÍ SLA|SLA BREACH/).first()).toBeVisible()
  await expect(page.getByText(/Služba běží, zatím žádné spory|service is running; no disputes yet/)).toHaveCount(0)
})
