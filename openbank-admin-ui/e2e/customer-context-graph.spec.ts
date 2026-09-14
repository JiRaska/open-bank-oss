// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const partyId = '11111111-1111-4111-8111-111111111111'

test('Customer 360 combines its supplied projection with one bounded live snapshot', async ({ page, context, baseURL }, testInfo) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMPLIANCE'])
  let projectionReads = 0
  let graphReads = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/auth/')) return route.continue()
    if (url.pathname.includes('/parties/search')) return route.fulfill({ json: { data: [
      { id: partyId, legalName: 'Synthetic Company — demo', status: 'ACTIVE' },
    ] } })
    if (/^\/api\/customer-360\/[^/]+\/graph$/.test(url.pathname)) {
      graphReads++
      return route.fulfill({ json: {
        accounts: [], cards: [], notifications: [], lendingApplications: [], amlCases: [],
        devices: [], documents: [], unavailable: [], truncated: [],
      } })
    }
    if (url.pathname.startsWith('/api/customer-360/')) {
      projectionReads++
      if (url.pathname !== `/api/customer-360/${partyId}`) {
        return route.fulfill({ status: 403, json: { available: false, error: 'forbidden' } })
      }
      return route.fulfill({ json: {
        available: true, partyId, asOf: '2026-09-13 10:00:00.000', excludedCount: 0,
        domains: [
          { aggregateType: 'account', events: 8, lastEventType: 'AccountOpened', lastOccurredAt: '2026-09-13 09:00:00.000' },
          { aggregateType: 'transaction', events: 24, lastEventType: 'TransactionBooked', lastOccurredAt: '2026-09-13 10:00:00.000' },
          { aggregateType: 'consent', events: 2, lastEventType: 'ConsentRevoked', lastOccurredAt: '2026-09-13 08:00:00.000' },
        ],
        accountIds: ['demo-operating-account', 'demo-savings-account'],
        consents: [{ consentId: 'demo-consent', status: 'REVOKED', scopes: ['ACCOUNTS_READ'] }],
      } })
    }
    return route.fulfill({ status: 503, json: { available: false, error: 'Synthetic preview: unrelated source unavailable' } })
  })
  await page.goto('/customer-360')
  await page.getByRole('textbox', { name: 'Search parties' }).fill('Synthetic')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await page.getByRole('button', { name: 'Select Synthetic Company — demo', exact: true }).click()
  const graph = page.getByRole('region', { name: 'Context graph', exact: true })
  await expect(graph).toBeVisible()
  await graph.getByRole('button', { name: 'Consent: demo-consent' }).click()
  await expect(graph.getByText('Projected state: REVOKED')).toBeVisible()
  await graph.getByRole('button', { name: 'Zoom in graph' }).click()
  await graph.getByRole('button', { name: 'Fit graph' }).click()
  await expect.poll(() => projectionReads).toBe(1)
  await expect.poll(() => graphReads).toBe(1)
  await graph.screenshot({ path: testInfo.outputPath('context-graph.png') })
  await graph.getByLabel('Node type').selectOption('account')
  await expect(graph.getByRole('button', { name: 'Consent: demo-consent' })).toHaveCount(0)
  await expect(graph.getByText('Projected state: REVOKED')).toHaveCount(0)
  expect(projectionReads).toBe(1)
  expect(graphReads).toBe(1)
  await page.getByRole('textbox', { name: 'Search parties' }).fill('22222222-2222-4222-8222-222222222222')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect.poll(() => projectionReads).toBe(2)
  await expect.poll(() => graphReads).toBe(2)
  await expect(graph).toHaveCount(0)
  await expect(page.getByText('Projected state: REVOKED')).toHaveCount(0)
})
