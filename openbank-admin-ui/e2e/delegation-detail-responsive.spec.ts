// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const GRANT_ID = '11111111-1111-4111-8111-111111111111'

test('delegation capabilities, ceilings and dates stay readable at 320px', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 320, height: 760 })
  await page.route(`**/api/delegations/${GRANT_ID}`, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      id: GRANT_ID,
      grantorPartyId: '22222222-2222-4222-8222-222222222222',
      granteePartyId: '33333333-3333-4333-8333-333333333333',
      grantorName: 'Corporate Treasury', granteeName: 'External Accountant',
      resourceType: 'ACCOUNT', resourceId: '44444444-4444-4444-8444-444444444444',
      capabilities: ['VIEW_BALANCE', 'VIEW_TRANSACTIONS'], approvalPolicy: 'MAKER_CHECKER',
      perTransactionLimit: { amount: 250000, currency: 'CZK' },
      dailyLimit: { amount: 500000, currency: 'CZK' }, monthlyLimit: null,
      validFrom: '2026-09-15T00:00:00Z', validTo: null, status: 'ACTIVE',
      createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-15T08:00:00Z',
      closedAt: null, closedReason: null,
    }),
  }))
  await page.route(`**/api/delegations/${GRANT_ID}/audit`, route => route.fulfill({ contentType: 'application/json', body: '[]' }))
  await page.route('**/api/delegations/check', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ allowed: true }) }))

  await page.goto(`/delegations/${GRANT_ID}`)
  const facts = page.getByTestId('delegation-detail-facts')
  await expect(facts).toBeVisible()
  expect(await facts.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  await expect(page.getByText('Corporate Treasury')).toBeVisible()
  await expect(page.getByText(/250[,.\s]?000 CZK/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
