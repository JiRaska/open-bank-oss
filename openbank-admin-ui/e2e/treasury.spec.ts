// SPDX-License-Identifier: Apache-2.0
// ADR-0315 / #10618 smoke: the treasury desk reaches the Treasury section. Backends are mocked (as
// in every spec here); the hide-approve-from-the-creator rule is covered by treasury-pages.test.tsx.
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const deal = (dealId: string, state: string, createdBy: string) => ({
  dealId, product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25,
  dayCount: 'ACT/360', days: 3, interest: 354.17, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
  state, createdBy, createdByType: 'HUMAN', submittedBy: createdBy, approvedBy: null, rationale: null, limitCheck: null,
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:00:00Z', history: [], journals: [],
})
const counterparties = [
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
]

test.beforeEach(async ({ context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_TREASURY_APPROVER'])
})

test('approver sees the blotter with the synthetic counterparty label and no new-deal button', async ({ page }) => {
  await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(counterparties),
  }))
  await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([deal('d-1', 'BOOKED', 'dana.dealer')]),
  }))
  await page.goto('/treasury/deals')
  const main = page.locator('main')
  await expect(main.getByText('dana.dealer')).toBeVisible({ timeout: 20_000 })
  await expect(main.getByText(/Simulovaná protistrana|Synthetic counterparty/)).toBeVisible()
  await expect(main.getByRole('link', { name: /Nový obchod|New deal/ })).toHaveCount(0)
})

test('approval inbox offers approve on a pending deal', async ({ page }) => {
  // The e2e session's access token is not a JWT, so the actor is unknown and the page must NOT
  // hide approve (the server's 422 is the control).
  await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(counterparties),
  }))
  await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([deal('d-2', 'PENDING_APPROVAL', 'dana.dealer')]),
  }))
  await page.goto('/treasury/approvals')
  const main = page.locator('main')
  await expect(main.getByText(/dana\.dealer/)).toBeVisible({ timeout: 20_000 })
  await expect(main.getByRole('button', { name: /^(Schválit|Approve)$/ })).toHaveCount(1)
})
