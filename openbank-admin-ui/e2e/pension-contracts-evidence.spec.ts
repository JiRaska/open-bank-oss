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

test('search requires two characters and a bare UUID opens its contract directly', async ({ page }) => {
  await page.goto('/pension')

  await page.getByRole('textbox', { name: 'Search contracts', exact: true }).fill('x')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText('Enter at least 2 characters: birth date, payment reference, product, or UUID.')).toBeVisible()
  await expect(page).toHaveURL(/\/pension$/)

  const uuid = '11111111-2222-4333-8444-555555555551'
  await page.getByRole('textbox', { name: 'Search contracts', exact: true }).fill(uuid)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/pension/contracts/${uuid}$`))
})

test('free-text search sends the query with the status and renders matching rows without navigation', async ({ page }) => {
  const requests: URL[] = []
  const match = '11111111-2222-4333-8444-555555555559'
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => {
    const url = new URL(route.request().url())
    requests.push(url)
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(url.searchParams.get('q') === '850505' ? [contract({ contractId: match })] : []),
    })
  })
  await page.goto('/pension')
  await page.getByRole('textbox', { name: 'Search contracts', exact: true }).fill('850505')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect.poll(() => requests.some(url => url.searchParams.get('q') === '850505'
    && url.searchParams.get('status') === 'ACTIVE' && url.searchParams.get('limit') === '100')).toBe(true)
  await expect(page.getByRole('row', { name: new RegExp(match) })).toBeVisible()
  await expect(page).toHaveURL(/\/pension$/)
})

test('a failed text search is unavailable rather than an empty result and clearing it recovers', async ({ page }) => {
  await page.unroute(CONTRACTS_URL)
  await page.route(CONTRACTS_URL, route => new URL(route.request().url()).searchParams.has('q')
    ? route.fulfill({ status: 502, body: 'bad gateway' })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
      contract({ contractId: '11111111-2222-4333-8444-555555555551' }),
    ]) }))
  await page.goto('/pension')
  const search = page.getByRole('textbox', { name: 'Search contracts', exact: true })
  await search.fill('unavailable-search')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByText(/Failed to load|not responding|is not deployed/)).toBeVisible()
  await expect(page.getByText('No data yet')).toHaveCount(0)
  await search.clear()
  await expect(page.getByRole('row', { name: /11111111-2222-4333-8444-555555555551/ })).toBeVisible()
  await expect(page.getByText(/Failed to load|not responding|is not deployed/)).toHaveCount(0)
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
