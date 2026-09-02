import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const amlCase = {
  id: 'aml-2026-0042',
  customerName: 'Acme Trading s.r.o.',
  customerType: 'CORPORATE',
  riskLevel: 'CRITICAL',
  status: 'ESCALATED',
  score: 94,
  timestamp: '2026-08-31T12:00:00Z',
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

  await expect(page.getByText('Acme Trading s.r.o.')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/AML case escalated|AML případ byl eskalován/)).toBeVisible()
  await expect(page.getByText('94', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit AML případy|Refresh AML cases/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('Acme Trading s.r.o.')).toBeVisible()
  await expect(page.getByText('94', { exact: true })).toBeVisible()
  await expect(page.getByText(/AML case escalated|AML případ byl eskalován/)).toBeVisible()
  await expect(page.getByText(/zatím neeviduje žádné AML případy|no AML cases recorded yet/)).toHaveCount(0)
})
