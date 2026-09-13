import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const message = {
  id: '8ab7a9bb-cffc-4fd9-8792-ce84eb0bd379',
  messageType: 'MT103',
  senderBic: 'KOMBCZPPXXX',
  receiverBic: 'DEUTDEFFXXX',
  amount: 85000.25,
  currency: 'EUR',
  status: 'VALIDATED',
  createdAt: '2026-08-31T12:00:00Z',
  reference: 'SWIFT-REF-0042',
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

test('keeps valid SWIFT lifecycle evidence visible after a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/swift-service/api/v1/swift/messages**', route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([message]) }))

  await page.goto('/swift')

  await expect(page.getByText('SWIFT-REF-0042')).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('85,000.25')).toBeVisible()
  await expect(page.getByText('VALIDATED', { exact: true })).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit SWIFT zprávy|Refresh SWIFT messages/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('SWIFT-REF-0042')).toBeVisible()
  await expect(page.getByText('85,000.25')).toBeVisible()
  await expect(page.getByText('VALIDATED', { exact: true })).toBeVisible()
  await expect(page.getByText(/zatím žádné SWIFT zprávy|no SWIFT messages yet/)).toHaveCount(0)
})

test('purges SWIFT evidence when the operator loses access', async ({ page }) => {
  let forbidden = false
  await page.route('**/api/svc/swift-service/api/v1/swift/messages**', route => forbidden
    ? route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ error: 'forbidden' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([message]) }))

  await page.goto('/swift')
  await expect(page.getByText('SWIFT-REF-0042')).toBeVisible({ timeout: 20_000 })

  forbidden = true
  await page.getByRole('button', { name: /Obnovit SWIFT zprávy|Refresh SWIFT messages/ }).click()

  await expect(page.getByText('SWIFT-REF-0042')).toHaveCount(0)
  await expect(page.getByText(/Vypršela relace|Session expired/, { exact: true })).toBeVisible()
  await expect(page.getByText(/poslední úspěšný snapshot|last successful snapshot/i)).toHaveCount(0)
})
