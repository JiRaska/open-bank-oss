// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// E2E evidence for the pension contracts surfaces (ADR-0334): the staff list/search page
// (/pension) and its failure-state contract. The pension route group previously had zero
// e2e coverage; this spec is the first browser evidence for it.
//
// The list calls pension-service through the BFF proxy
// (GET /api/svc/pension-service/api/v2/pension/contracts?status=&limit=), newest first.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

interface StrategyElection { strategyCode: string; effectiveFrom?: string | null; electedAt?: string | null }

interface ContractRow {
  contractId: string
  participantPartyId?: string | null
  productLine: string
  jurisdiction: string
  status: string
  schedule?: { amount: number | string; currency: string; frequency: string; employerAmount?: number | string | null } | null
  currentStrategy?: StrategyElection | null
  startDate?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

function contract(overrides: Partial<ContractRow> & { contractId: string }): ContractRow {
  return {
    productLine: 'DPS',
    jurisdiction: 'CZK',
    status: 'ACTIVE',
    schedule: { amount: 1000, currency: 'CZK', frequency: 'MONTHLY', employerAmount: 500 },
    currentStrategy: { strategyCode: 'BALANCED', effectiveFrom: '2024-01-01', electedAt: '2024-01-01T00:00:00Z' },
    startDate: '2024-01-01',
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  }
}

const CONTRACTS_URL = '**/api/svc/pension-service/api/v2/pension/contracts*'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(CONTRACTS_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      contract({ contractId: '11111111-2222-4333-8444-555555555551' }),
      contract({
        contractId: '11111111-2222-4333-8444-555555555552',
        productLine: 'DIP',
        status: 'SUSPENDED',
        currentStrategy: null,
        startDate: null,
      }),
    ]),
  }))
})

test('contract list renders product line, status label, strategy and start date', async ({ page }) => {
  await page.goto('/pension')

  await expect(page.getByRole('heading', { name: 'Pension contracts' })).toBeVisible()

  const first = page.getByRole('row', { name: /11111111-2222-4333-8444-555555555551/ })
  await expect(first).toContainText('DPS · CZK')
  await expect(first).toContainText('Active')
  await expect(first).toContainText('BALANCED')
  await expect(first).toContainText('2024-01-01')
  await expect(first.getByRole('link', { name: '11111111-2222-4333-8444-555555555551' }))
    .toHaveAttribute('href', '/pension/contracts/11111111-2222-4333-8444-555555555551')

  const second = page.getByRole('row', { name: /11111111-2222-4333-8444-555555555552/ })
  await expect(second).toContainText('Contributions paused')
  // Missing strategy and start date render the placeholder, never an empty cell.
  await expect(second).toContainText('—')
})

test('load more reveals the remaining client-side page', async ({ page }) => {
  await page.unroute(CONTRACTS_URL)
  const many = Array.from({ length: 27 }, (_, i) =>
    contract({ contractId: `99999999-0000-4000-8000-${String(i).padStart(12, '0')}` }))
  await page.route(CONTRACTS_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(many),
  }))

  await page.goto('/pension')

  // PAGE_SIZE is 25; the endpoint already returned everything (limit=100), so the
  // second page is a client-side reveal of rows the page already holds.
  await expect(page.getByRole('row')).toHaveCount(1 + 25)
  const loadMore = page.getByRole('button', { name: /Load more/ })
  await expect(loadMore).toBeVisible()
  await loadMore.click()
  await expect(page.getByRole('row')).toHaveCount(1 + 27)
  await expect(loadMore).toHaveCount(0)
})

test('status filter re-fetches the list with the chosen status', async ({ page }) => {
  const requestedStatuses: string[] = []
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => {
    requestedStatuses.push(new URL(route.request().url()).searchParams.get('status') ?? '')
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([contract({ contractId: '11111111-2222-4333-8444-555555555553', status: 'SUSPENDED' })]),
    })
  })

  await page.goto('/pension')
  // React StrictMode in dev double-invokes the load effect; every request must still
  // carry the default ACTIVE filter.
  await expect.poll(() => requestedStatuses.length).toBeGreaterThan(0)
  expect(new Set(requestedStatuses)).toEqual(new Set(['ACTIVE']))

  await page.getByLabel('Status filter').selectOption('SUSPENDED')

  await expect.poll(() => requestedStatuses).toContain('SUSPENDED')
  await expect(page.getByRole('row', { name: /11111111-2222-4333-8444-555555555553/ })).toContainText('Contributions paused')
})

test('search: a short query hints, free text searches in place, a UUID opens the contract', async ({ page }) => {
  const queries: (string | null)[] = []
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => {
    queries.push(new URL(route.request().url()).searchParams.get('q'))
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([contract({ contractId: '11111111-2222-4333-8444-555555555551' })]),
    })
  })

  await page.goto('/pension')
  const search = page.getByLabel('Search contracts')
  const submit = page.getByRole('button', { name: 'Filter list', exact: true })

  // Under two characters nothing is searched and nothing navigates; the hint says why.
  // The page can surface other role=alert banners (e.g. upstream health) — target the
  // validation hint by its text, not by role alone.
  await search.fill('x')
  await submit.click()
  await expect(page.getByText('Enter at least 2 characters: birth date, payment reference, or product.')).toBeVisible()
  await expect(page).toHaveURL(/\/pension$/)

  // Free text is a staff search (q) that stays on the list — it never navigates.
  await search.fill('DPS')
  await submit.click()
  await expect.poll(() => queries).toContain('DPS')
  await expect(page).toHaveURL(/\/pension$/)

  // A bare UUID opens the contract directly and is never sent as a search term.
  const uuid = '11111111-2222-4333-8444-555555555551'
  await search.fill(uuid)
  await submit.click()
  await expect(page).toHaveURL(new RegExp(`/pension/contracts/${uuid}$`))
  expect(queries).not.toContain(uuid)
})

test('a failed list load renders the unavailable state, never the no-data panel', async ({ page }) => {
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => route.fulfill({ status: 502, body: 'bad gateway' }))

  await page.goto('/pension')

  await expect(page.getByText(/Failed to load|not responding|is not deployed/)).toBeVisible()
  // "No data yet" would claim the query legitimately matched zero contracts.
  await expect(page.getByText('No data yet')).toHaveCount(0)
})

test('an empty result is the no-data state, not an error', async ({ page }) => {
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([]),
  }))

  await page.goto('/pension')

  await expect(page.getByText('No data yet')).toBeVisible()
  await expect(page.getByText(/Failed to load/)).toHaveCount(0)
})
