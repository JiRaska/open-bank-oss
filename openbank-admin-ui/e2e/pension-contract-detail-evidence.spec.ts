// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// E2E evidence for the pension contract detail page (/pension/contracts/[id]), the second
// pension surface after the contracts list covered by pension-contracts-evidence.spec.ts.
//
// The page fans out one contract read (pension-service) into three fund-side reads
// (pension-fund-service): holdings, transactions and the fund list for name resolution —
// plus two operator reads for participant changes. The contract under test below is
// PENDING_ACTIVATION, which also exercises the cooling-off notice the lifecycle records.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const ID = '11111111-2222-4333-8444-555555555551'
const CONTRACT = `**/api/svc/pension-service/api/v2/pension/contracts/${ID}`
const OPERATOR_BASE = `**/api/svc/pension-service/api/v2/pension/operator/contracts/${ID}`
const HOLDINGS = `**/api/svc/pension-fund-service/api/v1/contracts/${ID}/holdings**`
const TRANSACTIONS = `**/api/svc/pension-fund-service/api/v1/contracts/${ID}/transactions**`
const FUNDS = '**/api/svc/pension-fund-service/api/v1/funds**'

const CONTRACT_BODY = {
  contractId: ID,
  participantPartyId: 'party-7',
  productLine: 'DPS',
  jurisdiction: 'CZ',
  packVersion: 3,
  providerEntityId: 'entity-1',
  providerType: 'BANK',
  status: 'PENDING_ACTIVATION',
  schedule: { amount: 1700, currency: 'CZK', frequency: 'MONTHLY', employerAmount: 500 },
  currentStrategy: { strategyCode: 'BALANCED', effectiveFrom: '2024-06-01', electedAt: '2024-06-01T00:00:00Z' },
  beneficiaries: [{ name: 'Anna Druhá', sharePercent: 100, partyId: null }],
  startDate: '2024-06-01',
  createdAt: '2024-05-20T08:00:00Z',
  updatedAt: '2024-05-21T09:00:00Z',
}

const HOLDINGS_BODY = {
  contractId: ID,
  holdings: [
    { fundId: 'fund-1', units: 123.4567, navPerUnit: 1.2345, navDate: '2026-10-10', value: 152.4, currency: 'CZK' },
    { fundId: 'fund-2', units: 50, navPerUnit: null, navDate: null, value: null, currency: 'CZK' },
  ],
  pendingOrders: [1],
}

const TRANSACTIONS_BODY = [
  { id: 'tx-1', fundId: 'fund-1', type: 'SUBSCRIPTION', units: 123.4567, amount: 152.4, navPerUnit: 1.2345, pricedAt: '2026-10-10' },
]

const FUNDS_BODY = [
  { id: 'fund-1', name: 'Conservative Fund', isin: 'CZ0000000001', currency: 'CZK', riskClass: 2, managementFeeRate: 0.01, status: 'ACTIVE' },
]

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(CONTRACT, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONTRACT_BODY) }))
  await page.route(HOLDINGS, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(HOLDINGS_BODY) }))
  await page.route(TRANSACTIONS, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TRANSACTIONS_BODY) }))
  await page.route(FUNDS, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FUNDS_BODY) }))
  await page.route(`${OPERATOR_BASE}/contribution-schedule`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ amount: 1700, currency: 'CZK', frequency: 'MONTHLY', dayOfMonth: 15, status: 'ACTIVE' }),
  }))
  await page.route(`${OPERATOR_BASE}/beneficiaries`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{ name: 'Anna Druhá', sharePercent: 100 }]),
  }))
})

test('detail renders contract facts, cooling-off notice, holdings and transactions', async ({ page }) => {
  await page.goto(`/pension/contracts/${ID}`)

  await expect(page.getByRole('heading', { name: 'Pension contract' })).toBeVisible()
  await expect(page.getByText(ID)).toBeVisible()
  // Contract facts come from the pension-service read.
  await expect(page.getByText('Pending activation').first()).toBeVisible()
  await expect(page.getByText('DPS · CZ')).toBeVisible()
  await expect(page.getByText('1,700.00 CZK · MONTHLY')).toBeVisible()
  await expect(page.getByText('Anna Druhá 100 %').first()).toBeVisible()
  // PENDING_ACTIVATION carries the cooling-off notice — nobody can activate from here.
  await expect(page.getByText(/cooling-off period has ended and the first contribution/)).toBeVisible()

  // Fund-side reads: holdings table resolves fund names, totals the values and counts pending orders.
  const holdings = page.locator('section', { hasText: 'Unit holdings' })
  await expect(holdings).toContainText('Conservative Fund')
  await expect(holdings).toContainText('152.40 CZK')
  await expect(holdings.getByText('no NAV')).toBeVisible()
  await expect(holdings).toContainText('1 orders awaiting NAV')

  // Transactions render priced rows with resolved fund names.
  const txs = page.locator('section', { hasText: 'Unit transactions' })
  await expect(txs.getByRole('row', { name: /SUBSCRIPTION/ })).toContainText('Conservative Fund')
  await expect(txs.getByRole('row', { name: /SUBSCRIPTION/ })).toContainText('123.4567')
})

test('an invalid contract id renders not-found without calling the API', async ({ page }) => {
  let pensionCalls = 0
  await page.route('**/api/svc/pension-service/api/v2/pension/contracts/**', route => {
    pensionCalls += 1
    return route.fulfill({ status: 500, body: 'should never be called' })
  })

  await page.goto('/pension/contracts/not-a-uuid')

  await expect(page.getByText('Nothing found')).toBeVisible()
  // The UUID guard is client-side: a malformed id must die before any network call.
  expect(pensionCalls).toBe(0)
})

test('a failed fund-side read degrades the section, never the contract header', async ({ page }) => {
  await page.unroute(HOLDINGS)
  await page.route(HOLDINGS, route => route.fulfill({ status: 502, body: 'bad gateway' }))

  await page.goto(`/pension/contracts/${ID}`)

  // The contract itself loaded: the header and facts stay.
  await expect(page.getByRole('heading', { name: 'Pension contract' })).toBeVisible()
  await expect(page.getByText('DPS · CZ')).toBeVisible()
  // Only the holdings section reports the outage; transactions still render.
  const holdings = page.locator('section', { hasText: 'Unit holdings' })
  await expect(holdings.getByText(/Failed to load|not responding|is not deployed/)).toBeVisible()
  await expect(holdings.getByText('No data yet')).toHaveCount(0)
  const txs = page.locator('section', { hasText: 'Unit transactions' })
  await expect(txs.getByRole('row', { name: /SUBSCRIPTION/ })).toBeVisible()
})

test('a missing contract renders not-found, not a load failure', async ({ page }) => {
  await page.unroute(CONTRACT)
  await page.route(CONTRACT, route => route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not_found' }) }))

  await page.goto(`/pension/contracts/${ID}`)

  await expect(page.getByText('Nothing found')).toBeVisible()
  await expect(page.getByText(/Failed to load/)).toHaveCount(0)
  // Fund-side reads must not run for a contract that does not exist. (The sidebar
  // carries a "Unit holdings" nav link, so assert the section heading, not the text.)
  await expect(page.getByRole('heading', { name: 'Unit holdings' })).toHaveCount(0)
})
