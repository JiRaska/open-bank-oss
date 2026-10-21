// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const report = {
  asOf: '2026-10-14',
  generatedAt: new Date().toISOString(),
  tolerance: '0.01',
  currencies: [
    {
      currency: 'CZK',
      ledgerControlBalance: '1000000.00',
      subLedgerBookedSum: '1000000.00',
      difference: '0.00',
      withinTolerance: true,
      futureValueDatedPipeline: '5000.00',
    },
    {
      currency: 'EUR',
      ledgerControlBalance: '250000.00',
      subLedgerBookedSum: '250100.00',
      difference: '100.00',
      withinTolerance: false,
    },
  ],
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
})

test('renders the daily tie-out with drift evidence and refreshes from the service', async ({ page }) => {
  let calls = 0
  await page.route('**/api/svc/balance-service/api/v1/balances/reconciliation/latest**', route => {
    calls += 1
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(report) })
  })

  await page.goto('/day-end')

  // The two-currency fixture must render as an actionable drift signal, not a green pass:
  // EUR is outside tolerance and the banner names the drifted currency.
  await expect(page.getByText(/Drift detected in 1 currency|currencies|Zjištěn rozdíl v 1/).first()).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('EUR', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/Drifted currencies: EUR|Měny s rozdílem: EUR/)).toBeVisible()
  await expect(page.getByText('DRIFT', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/Ledger and sub-ledger are in agreement|Účetní kniha a sub-ledger souhlasí/)).toHaveCount(0)

  // The page polls on an interval; a manual refresh must re-hit the service.
  const before = calls
  await page.getByRole('button', { name: /Obnovit denní závěrku|Refresh day-end close/ }).click()
  await expect.poll(() => calls, { timeout: 15_000 }).toBeGreaterThan(before)
})

test('surfaces a never-run tie-out as an alert, not a calm no-data panel', async ({ page }) => {
  await page.route('**/api/svc/balance-service/api/v1/balances/reconciliation/latest**', route =>
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'no reconciliation run' }) }))

  await page.goto('/day-end')

  // A missing reconciliation record means the daily control has never run — the page
  // must render the danger alert (auditor-visible), never the gray empty state.
  await expect(page.getByText(/The daily tie-out has never run|Denní tie-out dosud neproběhl/)).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/the scheduler has not run, or every run failed|plánovač buď neběžel, nebo každý běh selhal/)).toBeVisible()
})

test('clears the stale tie-out and names the service outage on a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route('**/api/svc/balance-service/api/v1/balances/reconciliation/latest**', route => unavailable
    ? route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'upstream_unreachable' }) })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(report) }))

  await page.goto('/day-end')

  await expect(page.getByText('DRIFT', { exact: true }).first()).toBeVisible({ timeout: 20_000 })

  unavailable = true
  await page.getByRole('button', { name: /Obnovit denní závěrku|Refresh day-end close/ }).click()

  // A failed refresh must not leave yesterday's drift on screen as if it were current —
  // the report is cleared and the outage is named as a Balance-service problem.
  await expect(page.getByText(/Balance-service is not responding|Balance-service neodpovídá/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByText('DRIFT', { exact: true })).toHaveCount(0)
})
