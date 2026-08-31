import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const aggregate = {
  cnb: { rates: [], syncedAt: '2026-08-31T12:00:00Z', error: null },
  ecb: { rates: [], syncedAt: '2026-08-31T12:00:00Z', error: null },
  fxService: { status: 'up', rates: [], conversions: [] },
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/auth/session', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      user: {
        name: 'E2E Operator',
        email: 'e2e-operator@openbank.test',
        roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_AUDITOR', 'ROLE_COMPLIANCE', 'ROLE_PAYMENTS'],
        accessToken: 'e2e-fake-access-token',
      },
      expires: '2099-01-01T00:00:00.000Z',
    }),
  }))
  await page.route('**/api/fx/rates', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(aggregate),
  }))
})

test('renders one chronological, educational three-month CNB trend', async ({ page }) => {
  await page.route('**/api/fx/history/EUR/CZK', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { timestamp: '2026-08-29T00:00:00Z', rate: 25.5 },
      { timestamp: '2026-05-29T00:00:00Z', rate: 25 },
    ]),
  }))

  await page.goto('/fx')

  await expect(page.getByRole('img', { name: /Kurz se změnil o 2\.00 procenta|Rate changed by 2\.00 percent/ })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('+2.00 %')).toBeVisible()
  await expect(page.getByText(/Orientační střed ČNB; nejde o historickou závaznou klientskou nabídku|Indicative CNB mid-rate; not a binding historical customer quote/)).toBeVisible()
})

test('does not mislabel an outage as missing fixings and can retry', async ({ page }) => {
  let attempts = 0
  let recovered = false
  await page.route('**/api/fx/history/EUR/CZK', route => {
    attempts += 1
    return recovered
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
        { timestamp: '2026-05-29T00:00:00Z', rate: 25 },
        { timestamp: '2026-08-29T00:00:00Z', rate: 25.5 },
      ]) })
      : route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
  })

  await page.goto('/fx')
  await expect(page.getByText(/Historický trend teď nelze načíst|Historical trend is unavailable/)).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/není dost historických fixingů|not enough historical fixings/)).toHaveCount(0)
  const attemptsBeforeRetry = attempts
  recovered = true
  await page.getByRole('button', { name: /Zkusit znovu|Try again/ }).click()
  await expect(page.getByText('+2.00 %')).toBeVisible()
  expect(attempts).toBeGreaterThan(attemptsBeforeRetry)
})
