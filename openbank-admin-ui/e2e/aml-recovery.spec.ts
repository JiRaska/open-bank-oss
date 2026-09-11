import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const amlCase = {
  id: 'aml-2026-0042',
  partyId: 'party-acme-42',
  accountId: 'account-7',
  transactionId: 'transaction-9',
  customerReference: 'ACME-TRADING-42',
  screeningType: 'TRANSACTION_MONITORING',
  riskLevel: 'CRITICAL',
  status: 'ESCALATED',
  alertCode: 'TXN_THRESHOLD',
  alertDetail: 'Aggregate threshold exceeded',
  matchedEntity: null,
  decisionReason: 'MLRO review required',
  assignedAnalyst: 'analyst-7',
  decidedBy: 'analyst-4',
  screenedAt: '2026-08-31T12:00:00Z',
  decidedAt: '2026-08-31T12:05:00Z',
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
        name: 'E2E Compliance',
        email: 'e2e-compliance@openbank.test',
        roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_COMPLIANCE'],
        accessToken: 'e2e-fake-access-token',
      },
      expires: '2099-01-01T00:00:00.000Z',
    }),
  }))
})

test('keeps escalated AML evidence visible after a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/aml-service/api/v1/aml/cases**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([amlCase]) }))

  await page.goto('/aml')

  await expect(page.getByText('ACME-TRADING-42')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/AML case escalated|AML případ byl eskalován/)).toBeVisible()
  await expect(page.getByText('TXN_THRESHOLD', { exact: true })).toBeVisible()
  await expect(page.getByText('analyst-7')).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit AML případy|Refresh AML cases/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('ACME-TRADING-42')).toBeVisible()
  await expect(page.getByText('TXN_THRESHOLD', { exact: true })).toBeVisible()
  await expect(page.getByText(/AML case escalated|AML případ byl eskalován/)).toBeVisible()
  await expect(page.getByText(/zatím neeviduje žádné AML případy|no AML cases recorded yet/)).toHaveCount(0)
})
