// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// The lending console's contract with an underwriter is honesty about numbers:
// capped lists must never read as book totals, an unconfirmed figure is an em
// dash — never a zero — and a failed load must recover into confirmed figures
// on refresh instead of leaving dashes behind. These tests pin those three
// promises against the real page, not a re-implementation of it.

const money = (amount: number, code: string) => ({ amount, currency: { code } })

const RECENT = '**/api/svc/lending-service/api/v1/lending/applications/recent*'
const LOANS = '**/api/svc/lending-service/api/v1/lending/loans/active*'
const APP_SUMMARY = '**/api/svc/lending-service/api/v1/lending/applications/summary'
const LOAN_SUMMARY = '**/api/svc/lending-service/api/v1/lending/loans/summary'

function bookRoutes(page: import('@playwright/test').Page) {
  return page.route(RECENT, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { id: '00000000-0000-4000-8000-000000000001', partyId: '00000000-0000-4000-8000-0000000000a1', status: 'DECISION_PENDING', createdAt: new Date(Date.now() - 96 * 3_600_000).toISOString(), requestedAmount: money(250000, 'EUR') },
      { id: '00000000-0000-4000-8000-000000000002', partyId: '00000000-0000-4000-8000-0000000000a2', status: 'ASSESSMENT', createdAt: new Date().toISOString(), requestedAmount: money(80000, 'EUR') },
      { id: '00000000-0000-4000-8000-000000000003', partyId: '00000000-0000-4000-8000-0000000000a3', status: 'DISBURSED', createdAt: new Date().toISOString(), requestedAmount: money(120000, 'EUR') },
    ]),
  })).then(() => page.route(LOANS, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { id: '00000000-0000-4000-8000-000000000101', partyId: '00000000-0000-4000-8000-0000000000b1', status: 'ACTIVE', principal: money(300000, 'EUR'), disbursedAt: '2026-09-01T10:00:00Z' },
      { id: '00000000-0000-4000-8000-000000000102', partyId: '00000000-0000-4000-8000-0000000000b2', status: 'DELINQUENT', principal: money(50000, 'EUR'), disbursedAt: '2026-07-15T10:00:00Z' },
    ]),
  })))
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('renders whole-book KPIs from the aggregate endpoints and drills down by stage', async ({ page }) => {
  await bookRoutes(page)
  await page.route(APP_SUMMARY, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { status: 'DECISION_PENDING', count: 7, oldestCreatedAt: new Date(Date.now() - 96 * 3_600_000).toISOString(), requested: [{ currency: 'EUR', amount: 1750000 }] },
      { status: 'ASSESSMENT', count: 3, oldestCreatedAt: new Date().toISOString(), requested: [{ currency: 'EUR', amount: 240000 }] },
      { status: 'DISBURSED', count: 40, requested: [{ currency: 'EUR', amount: 4800000 }] },
    ]),
  }))
  await page.route(LOAN_SUMMARY, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { status: 'ACTIVE', count: 38, principal: [{ currency: 'EUR', amount: 11400000 }] },
      { status: 'DELINQUENT', count: 2, principal: [{ currency: 'EUR', amount: 90000 }] },
      { status: 'SETTLED', count: 12, principal: [{ currency: 'EUR', amount: 2100000 }] },
    ]),
  }))

  await page.goto('/lending')
  await expect(page.getByRole('heading', { name: 'Lending console' })).toBeVisible()
  // Whole-book mode: the pipeline stops deriving counts from the capped page,
  // and the KPI tiles are aggregates, not capped-list arithmetic.
  await expect(page.getByTestId('cap-note').first()).toHaveText('the whole application book')
  await expect(page.locator('.grid-4 > *').nth(0)).toContainText('38')
  await expect(page.locator('.grid-4 > *').nth(1)).toContainText('10')
  await expect(page.locator('.grid-4 > *').nth(2)).toContainText('1')
  await expect(page.locator('.grid-4 > *').nth(3)).toContainText('2')

  // Stage drill-down filters the queue to that state and can be cleared again.
  // The stage is an SVG <g> whose bounding-box centre sits between the nodes, so a
  // Playwright actionability click can land on empty canvas — dispatch the click
  // straight at the element instead.
  await page.getByTestId('stage-DECISION_PENDING').dispatchEvent('click')
  // EntityChip truncates the uuid for display — locate rows by the party link target instead.
  const partyA = (id: string) => page.locator(`tbody a[href="/parties/00000000-0000-4000-8000-0000000000${id}"]`)
  await expect(partyA('a1')).toBeVisible()
  await expect(partyA('a2')).toBeHidden()
  await page.getByTestId('clear-stage').click()
  await expect(partyA('a2')).toBeVisible()

  // The portfolio tab maps raw loan states to human labels and surfaces trouble.
  await page.getByRole('button', { name: 'Show portfolio' }).click()
  await expect(page.getByText('Delinquent', { exact: true })).toBeVisible()
})

test('flags the cap when aggregates are absent instead of reading a capped page as the book', async ({ page }) => {
  // The capped warning fires only when the page is full (items.length >= cap),
  // so serve exactly 100 applications — the server-side clamp this page fetches under.
  await page.route(RECENT, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(Array.from({ length: 100 }, (_, i) => ({
      id: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
      partyId: '00000000-0000-4000-8000-0000000000a1',
      status: i < 99 ? 'ASSESSMENT' : 'DECISION_PENDING',
      createdAt: new Date().toISOString(),
      requestedAmount: money(1000 + i, 'EUR'),
    }))),
  }))
  await page.route(LOANS, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { id: '00000000-0000-4000-8000-000000000101', partyId: '00000000-0000-4000-8000-0000000000b1', status: 'ACTIVE', principal: money(300000, 'EUR'), disbursedAt: '2026-09-01T10:00:00Z' },
      { id: '00000000-0000-4000-8000-000000000102', partyId: '00000000-0000-4000-8000-0000000000b2', status: 'DELINQUENT', principal: money(50000, 'EUR'), disbursedAt: '2026-07-15T10:00:00Z' },
    ]),
  }))
  // Absent = the deployed service predates #3294. The lists still render, the
  // pipeline derives from them, and the warning says older applications are NOT
  // in the numbers — a staffing decision must never rest on a silent cap.
  await page.route(APP_SUMMARY, route => route.fulfill({ status: 404, body: '{}' }))
  await page.route(LOAN_SUMMARY, route => route.fulfill({ status: 404, body: '{}' }))

  await page.goto('/lending')
  await expect(page.getByTestId('cap-note').first()).toContainText('older applications are NOT in these numbers')
  // Derived mode: KPIs are capped-list arithmetic (2 fetched loans, 100 fetched
  // applications all in flight, none aging, 1 troubled) and the aging hint says
  // it is a derived figure.
  await expect(page.locator('.grid-4 > *').nth(0)).toContainText('2')
  await expect(page.locator('.grid-4 > *').nth(1)).toContainText('100')
  await expect(page.locator('.grid-4 > *').nth(2)).toContainText('0')
  await expect(page.locator('.grid-4 > *').nth(3)).toContainText('1')
})

test('an unreachable service shows dashes and a banner, and refresh recovers into confirmed figures', async ({ page }) => {
  let listsOk = false
  await page.route(RECENT, route => listsOk
    ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    : route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"upstream_unreachable"}' }))
  await page.route(LOANS, route => listsOk
    ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    : route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"upstream_unreachable"}' }))
  await page.route(APP_SUMMARY, route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route(LOAN_SUMMARY, route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))

  await page.goto('/lending')
  await expect(page.getByText('lending-service is unreachable.')).toBeVisible()
  // An unconfirmed figure is an em dash, never a fabricated zero.
  await expect(page.locator('.grid-4 > *').nth(0)).toContainText('—')

  listsOk = true
  await page.getByRole('button', { name: 'Refresh lending' }).click()
  // A genuinely empty book reads as 0 the moment the service answers — the
  // placeholder tracks whether we were told, not whether the total is zero.
  await expect(page.getByText('lending-service is unreachable.')).toBeHidden()
  await expect(page.locator('.grid-4 > *').nth(0)).toContainText('0')
  await expect(page.locator('.grid-4 > *').nth(1)).toContainText('0')
})
