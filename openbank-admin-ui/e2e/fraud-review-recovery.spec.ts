import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const reviewRecord = {
  scoreId: 'score-001',
  amount: 125000,
  currency: 'CZK',
  rail: 'SCT_INST',
  accountId: 'account-12345678',
  counterpartyId: 'party-87654321',
  verdict: 'REVIEW',
  score: 91,
  ruleVersion: 'fraud-rules-2026.08',
  createdAt: '2026-08-31T08:15:00Z',
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

test('retains the bounded fraud evidence snapshot when refresh fails', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/fraud-service/api/v1/fraud/review-queue**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([reviewRecord]) }))

  await page.goto('/fraud')

  await expect(page.getByText('125,000 CZK')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('fraud-rules-2026.08')).toBeVisible()
  await expect(page.getByText('91', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit frontu podvodů|Refresh fraud queue/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible()
  await expect(page.getByText('125,000 CZK')).toBeVisible()
  await expect(page.getByText('fraud-rules-2026.08')).toBeVisible()
  await expect(page.getByText(/Fronta je prázdná|Queue is empty/)).toHaveCount(0)
})
