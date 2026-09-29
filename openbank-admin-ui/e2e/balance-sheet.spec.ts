// SPDX-License-Identifier: Apache-2.0
// #10618 smoke: the finance persona reaches the Balance sheet & risk section, and the ledger
// backfill lists its four-eyes requests. Backends are mocked (as in every spec here); the
// hide-approve-from-the-proposer rule is covered by balance-sheet-pages.test.tsx.
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const request = (id: string, state: string, proposedBy: string) => ({
  id, state, cutoverDate: '2026-09-25', planHash: 'h', loanCount: 2, legCount: 13, proposedBy,
  decidedBy: null, decisionReason: null, executedBy: null, lastResult: null,
  proposedAt: '2026-09-25T08:00:00Z', decidedAt: null, executedAt: null,
})

test.beforeEach(async ({ context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_FINANCE'])
})

test('finance sees the snapshot list with the synthetic provenance label', async ({ page }) => {
  await page.route('**/api/svc/risk-engine/api/v1/risk/snapshots**', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ runs: [{ id: 'run-1', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'synthetic', status: 'TIED_OUT', positionCount: 3, mismatchCount: 0 }] }),
  }))
  await page.goto('/balance-sheet/snapshots')
  const main = page.locator('main')
  await expect(main.getByText('2026-09-30')).toBeVisible({ timeout: 20_000 })
  await expect(main.getByText(/Syntetická data|Synthetic data/)).toBeVisible()
  await expect(main.getByRole('button', { name: /Sestavit snímek|Build snapshot/ })).toHaveCount(0)
})

test('ledger backfill lists requests and offers approve on a PROPOSED request only', async ({ page }) => {
  // The e2e session's access token is not a JWT, so the actor is unknown and the page must NOT
  // hide approve (the server's 422 is the control): the PROPOSED row offers it, the EXECUTED not.
  await page.route('**/api/svc/lending-service/api/v1/lending/ledger-backfill/requests**', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ requests: [request('r-1', 'PROPOSED', 'someone'), request('r-2', 'EXECUTED', 'other')] }),
  }))
  await page.goto('/balance-sheet/ledger-backfill')
  const main = page.locator('main')
  await expect(main.getByText('someone')).toBeVisible({ timeout: 20_000 })
  await expect(main.getByRole('button', { name: /^(Schválit|Approve)$/ })).toHaveCount(1)
})
