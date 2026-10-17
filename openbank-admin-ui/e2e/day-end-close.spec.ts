// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// The Closings page (/day-end) drives two money-path surfaces with zero e2e coverage:
// the EoD per-currency tie-out (balance-service BFF) and the EoM monthly statement
// close runs through the dedicated session-gated /api/closings relay. The fixtures
// below satisfy the page's strict client contracts (see src/lib/closings/evidence.ts):
// COMPLETED requires finishedAt >= startedAt and pocketsFailed === 0,
// COMPLETED_WITH_FAILURES requires pocketsFailed > 0.

const RUN_A = '3f8b2c1a-1111-4111-8111-111111111111' // latest, with failures
const RUN_B = '7a4d9e3b-2222-4222-8222-222222222222' // older, clean

const CLOSE_RUNS = [
  {
    id: RUN_A,
    trigger: 'SCHEDULED',
    status: 'COMPLETED_WITH_FAILURES',
    periodFrom: '2026-09-01',
    periodTo: '2026-09-30',
    accountsEnumerated: 1284,
    pocketsClosed: 41,
    pocketsFailed: 1,
    pocketsSkipped: 2,
    startedAt: '2026-10-01T01:00:00Z',
    finishedAt: '2026-10-01T01:07:30Z',
  },
  {
    id: RUN_B,
    trigger: 'MANUAL',
    status: 'COMPLETED',
    periodFrom: '2026-08-01',
    periodTo: '2026-08-31',
    accountsEnumerated: 1271,
    pocketsClosed: 39,
    pocketsFailed: 0,
    pocketsSkipped: 0,
    startedAt: '2026-09-01T01:00:00Z',
    finishedAt: '2026-09-01T01:06:12Z',
  },
]

const ACCEPTED_RUN = {
  ...CLOSE_RUNS[1],
  id: 'c51f8a2d-3333-4333-8333-333333333333',
  trigger: 'MANUAL',
  status: 'RUNNING',
  periodFrom: null,
  periodTo: null,
  pocketsClosed: 0,
  pocketsSkipped: 0,
  startedAt: '2026-10-14T20:00:00Z',
  finishedAt: null,
}

const FAILURES = [
  {
    id: '9d2c6f44-4444-4444-8444-444444444444',
    runId: RUN_A,
    accountId: '55555555-5555-4555-8555-555555555555',
    pocketCurrency: 'CZK',
    periodFrom: '2026-09-01',
    periodTo: '2026-09-30',
    reason: 'RECONCILIATION',
    detail: 'Ledger control 1 000,00 vs sub-ledger 999,50 CZK po zaokrouhlení.',
    failedAt: '2026-10-01T01:05:41Z',
  },
]

const RECONCILIATION = {
  asOf: '2026-10-14',
  generatedAt: '2026-10-14T18:30:00Z',
  tolerance: '0.01',
  currencies: [
    {
      currency: 'CZK',
      ledgerControlBalance: '1000000.00',
      subLedgerBookedSum: '1000000.00',
      difference: '0.00',
      withinTolerance: true,
      futureValueDatedPipeline: '1250.50',
    },
  ],
}

const CLOSINGS_URL = '**/api/closings/runs*'
const FAILURES_URL = '**/api/closings/runs/*/failures'
const RECONCILIATION_URL = '**/api/svc/balance-service/api/v1/balances/reconciliation/latest'

function fulfillJson(route: { fulfill: (r: object) => unknown }, status: number, body: unknown) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('EoM close run history renders the latest summary and expandable per-pocket failures', async ({ page }) => {
  let runsRequests = 0
  await page.route(CLOSINGS_URL, route => {
    if (route.request().method() !== 'GET') return fulfillJson(route, 405, { error: 'method' })
    runsRequests += 1
    return fulfillJson(route, 200, CLOSE_RUNS)
  })
  await page.route(FAILURES_URL, route => fulfillJson(route, 200, FAILURES))
  await page.route(RECONCILIATION_URL, route => fulfillJson(route, 200, RECONCILIATION))

  await page.goto('/day-end?tab=eom')
  await expect(page.getByRole('heading', { name: /Závěrky|Closings/ })).toBeVisible()

  // Latest-run summary KPIs come from the newest run (RUN_A: 41 closed, 1 failed).
  await expect(page.getByText(/Poslední běh|Latest run/).first()).toBeVisible()
  await expect(page.getByText(/Dokončeno s chybami|Completed with failures/).first()).toBeVisible()
  await expect(page.locator('div', { hasText: /Uzavřeno kapes|Pockets closed/ }).getByText('41', { exact: true }).first()).toBeVisible()

  // Run history lists both runs; the failed one exposes its failure detail on expand.
  await expect(page.getByRole('cell', { name: /Plánovaný|Scheduled/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: /Ruční|Manual/ })).toBeVisible()
  await page.getByRole('button', { name: /Zobrazit chyby|Show failures/ }).click()
  await expect(page.getByText(/Rekonciliace nesedí|Reconciliation mismatch/)).toBeVisible()
  await expect(page.getByText(/55555555-5555-4555-8555-555555555555/)).toBeVisible()

  // The page's own state check is recorded in the operator check trail (survives
  // reloads; the raw status is part of the entry text).
  await expect(page.getByText(/Latest run: COMPLETED_WITH_FAILURES · 1 failed/)).toBeVisible()
  expect(runsRequests).toBeGreaterThanOrEqual(1)
})

test('EoM outage degrades into the unreachable state and the retry recovers', async ({ page }) => {
  let available = false
  await page.route(CLOSINGS_URL, route => {
    if (route.request().method() !== 'GET') return fulfillJson(route, 405, { error: 'method' })
    if (!available) {
      return fulfillJson(route, 502, { error: 'upstream_unreachable' })
    }
    return fulfillJson(route, 200, CLOSE_RUNS)
  })
  await page.route(RECONCILIATION_URL, route => fulfillJson(route, 200, RECONCILIATION))

  await page.goto('/day-end?tab=eom')
  // The EoM panel settles into the unreachable state; the EoD panel above it stays green.
  await expect(page.getByText(/Statement-service neodpovídá|Statement-service is not responding/).first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText(/Close unavailable \(unreachable\)/)).toBeVisible()

  available = true
  await page.getByRole('button', { name: /Obnovit měsíční závěrku|Refresh month-end close/ }).click()
  await expect(page.getByText(/Dokončeno s chybami|Completed with failures/).first()).toBeVisible()
  await expect(page.getByText(/Statement-service neodpovídá|Statement-service is not responding/)).toHaveCount(0)
})

test('operator catch-up trigger posts through the review dialog and shows the accepted run', async ({ page }) => {
  let posts = 0
  await page.route(CLOSINGS_URL, route => {
    if (route.request().method() === 'POST') {
      posts += 1
      return fulfillJson(route, 202, ACCEPTED_RUN)
    }
    return fulfillJson(route, 200, CLOSE_RUNS)
  })
  await page.route(RECONCILIATION_URL, route => fulfillJson(route, 200, RECONCILIATION))

  await page.goto('/day-end?tab=eom')
  await expect(page.getByRole('button', { name: /Zkontrolovat catch-up uzávěrku|Review catch-up close/ })).toBeVisible()

  await page.getByRole('button', { name: /Zkontrolovat catch-up uzávěrku|Review catch-up close/ }).click()
  await page.getByRole('button', { name: /Potvrdit spuštění|Confirm run/ }).click()

  await expect(page.getByText(/Catch-up uzávěrka přijata|Catch-up close run accepted/)).toBeVisible()
  expect(posts).toBe(1)
  // The check trail records the manual trigger (the optimistic RUNNING row is
  // replaced by the next history refresh, so it is not the stable assertion).
  await expect(page.getByText(/Ručně spuštěna catch-up uzávěrka|Catch-up close triggered manually/)).toBeVisible()
})

test('EoD tie-out renders per-currency reconciliation and flags drift', async ({ page }) => {
  const drifted = {
    ...RECONCILIATION,
    currencies: [
      { ...RECONCILIATION.currencies[0], difference: '12.50', withinTolerance: false },
    ],
  }
  await page.route(CLOSINGS_URL, route => fulfillJson(route, 200, CLOSE_RUNS))
  await page.route(RECONCILIATION_URL, route => fulfillJson(route, 200, drifted))

  await page.goto('/day-end')
  await expect(page.getByText(/Zjištěn rozdíl v 1 měně|Drift detected in 1 currency/)).toBeVisible()
  await expect(page.getByText(/ROZDÍL|DRIFT/).first()).toBeVisible()
  await expect(page.getByText(/Měny s rozdílem: CZK|Drifted currencies: CZK/)).toBeVisible()

  // Switching to the EoM tab: the run history is unaffected by the EoD drift.
  await page.getByRole('button', { name: /Měsíční uzávěrka \(EoM\)|Month-end \(EoM\)/ }).click()
  await expect(page.getByText(/Dokončeno s chybami|Completed with failures/).first()).toBeVisible()
})
