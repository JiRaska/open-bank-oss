// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// Trace Explorer evidence suite (issue #5904 family). The page reads the only window the bank
// has into its distributed traces, and every one of these states has regressed into a lie at
// least once: a held response rendering as an empty grid, a Tempo outage reading as "no traces",
// a failed span fetch reading as "this trace is empty". The unit tests pin the parsers; these
// tests pin the rendered states an operator actually believes.

const SEARCH_URL = '**/api/tempo/api/search**'
const TRACE_URL = '**/api/tempo/api/traces/**'

const ONE_TRACE = {
  traces: [
    {
      traceID: '4bf92f3577b34da6a3ce929d0e0e4736',
      rootServiceName: 'transaction-service',
      rootTraceName: 'POST /api/v1/transactions',
      startTimeUnixNano: '1725000000000000000',
      durationMs: 42,
    },
  ],
}

test('an empty Tempo search renders the honest no_data state, not an empty Recent traces card', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(SEARCH_URL, route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ traces: [] }) }))

  await page.goto('/observability/traces', { waitUntil: 'domcontentloaded' })

  // The no_data copy names the feature — the state an operator must be able to tell apart
  // from "Tempo is down" and from "still loading".
  await expect(page.getByText('No data yet: Distributed tracing')).toBeVisible()
  await expect(page.getByText('The data source is available but does not contain any records yet.')).toBeVisible()
  // The pre-fix failure shape: a grid header with an empty count and an empty list.
  await expect(page.getByText('Recent traces ()')).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Recent traces list' })).toHaveCount(0)
  // And it must not read as an outage either.
  await expect(page.getByText('tempo (observability) is not responding')).toHaveCount(0)
})

test('a Tempo outage renders unreachable, never the no_data panel', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(SEARCH_URL, route => route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"upstream"}' }))

  // Resolve the real cookie-backed session before measuring the Tempo state. The
  // AuthGuard hides that state while identity is loading independently of Tempo.
  const sessionReady = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/auth/session')
  await page.goto('/observability/traces', { waitUntil: 'domcontentloaded' })
  expect((await sessionReady).status()).toBe(200)

  // 502 maps to unreachable — the whole point of issue #5904: an outage must not render the
  // same panel as a healthy Tempo that genuinely holds no traces, or every deploy of the
  // observability stack silently reads as "nothing happened yet".
  await expect(page.getByText('tempo (observability) is not responding')).toBeVisible()
  await expect(page.getByText('No data yet: Distributed tracing')).toHaveCount(0)
})

test('the first fetch in flight renders loading, not the empty grid and not no_data', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let releaseSearch!: () => void
  const heldSearch = new Promise<void>(resolve => { releaseSearch = resolve })
  await page.route(SEARCH_URL, async route => {
    await heldSearch
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(ONE_TRACE) })
  })

  await page.goto('/observability/traces', { waitUntil: 'domcontentloaded' })
  // role=status carries no accessible name from its contents in Chromium, so the page's own
  // testid is the stable hook; the visible copy is asserted below via text.
  await expect(page.locator('[data-testid="trace-list-loading"]')).toBeVisible()
  await expect(page.getByText('Loading traces from Tempo…')).toBeVisible()
  // Held must not collapse into the empty grid ("Recent traces ()") nor into no_data.
  await expect(page.getByText('Recent traces ()')).toHaveCount(0)
  await expect(page.getByText('No data yet: Distributed tracing')).toHaveCount(0)

  releaseSearch()
  await expect(page.getByRole('region', { name: 'Recent traces list' })).toBeVisible()
  await expect(page.getByText('transaction-service')).toBeVisible()
})

test('a failed span fetch renders unreachable for the waterfall, never the empty-trace panel', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(SEARCH_URL, route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(ONE_TRACE) }))
  await page.route(TRACE_URL, route => route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"upstream"}' }))

  await page.goto('/observability/traces', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('region', { name: 'Recent traces list' })).toBeVisible()

  await page.getByRole('button', { name: /transaction-service — POST \/api\/v1\/transactions/ }).click()

  // The exact regression issue #5904 fixed: setSpans([]) rendered the same "No data yet" panel
  // as a trace that genuinely carries no spans, so a 502 from Tempo read as "this trace is
  // empty". The list stays (it was verified); the waterfall must say unreachable, not no_data.
  await expect(page.getByText('tempo (observability) is not responding')).toBeVisible()
  await expect(page.getByText('No data yet: Trace spans')).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Recent traces list' })).toBeVisible()
})

test('a malformed Tempo search payload renders the error state, not no_data and not an empty grid', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  // parseTempoSearch rejects this (duplicate traceID), and the page must surface that as a
  // failure of the evidence pipeline, never as "Tempo holds no traces".
  await page.route(SEARCH_URL, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ traces: [
      { traceID: '4bf92f3577b34da6a3ce929d0e0e4736', rootServiceName: 'a', rootTraceName: 'x', startTimeUnixNano: '1' },
      { traceID: '4bf92f3577b34da6a3ce929d0e0e4736', rootServiceName: 'b', rootTraceName: 'y', startTimeUnixNano: '2' },
    ] }),
  }))

  await page.goto('/observability/traces', { waitUntil: 'domcontentloaded' })

  await expect(page.getByText('Failed to load: Distributed tracing')).toBeVisible()
  await expect(page.getByText('No data yet: Distributed tracing')).toHaveCount(0)
})
