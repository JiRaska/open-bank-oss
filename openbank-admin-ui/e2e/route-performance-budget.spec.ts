// SPDX-License-Identifier: Apache-2.0
//
// ADR-0370 U6 — per-route performance budget, measured in the browser on the production build.
//
// Two numbers per route, both read from what the page actually did rather than from build
// manifests (Next 16 no longer writes a per-route client manifest):
//   jsBytes — decoded bytes of every /_next/static JS file the route loaded
//   lcpMs   — Largest Contentful Paint from a buffered PerformanceObserver
// Budgets live in e2e/route-performance-budget.json. Every BFF call is answered with an empty
// JSON body (service-proxy calls are aborted) so the number measures the page, not a backend.
// INP is not measured: it needs a real interaction per route, and an invented click would be a
// number about the click.
//
// Tagged @perf: excluded from the gating e2e run, run by `OPENBANK_E2E_PERF=only`.
// OPENBANK_PERF_RECORD=<file> writes the measurements as JSON instead of asserting.

import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from './helpers/test'
import { signInWithRoles } from './helpers/auth'

interface Budget { jsBytes: number; lcpMs: number }
const BUDGET_FILE = path.join(path.dirname(fileURLToPath(import.meta.url)), 'route-performance-budget.json')
const budgets = JSON.parse(readFileSync(BUDGET_FILE, 'utf8')).routes as Record<string, Budget>
const measured: Record<string, { jsBytes: number; lcpMs: number }> = {}

test.describe.configure({ mode: 'serial' })

test.afterAll(() => {
  const record = process.env.OPENBANK_PERF_RECORD
  if (record) writeFileSync(record, `${JSON.stringify(measured, null, 2)}\n`)
})

for (const [route, budget] of Object.entries(budgets)) {
  test(`@perf ${route} stays within its JS and LCP budget`, async ({ page, context, baseURL }) => {
    await signInWithRoles(context, baseURL!, ['ROLE_ADMIN'])
    // Service-proxy calls are aborted, not faked: an invented body would be checked (and rejected)
    // by the U1 mock-contract guard, and the budget is about the page, not the data.
    await page.route('**/api/**', r => {
      const url = r.request().url()
      if (url.includes('/api/auth/')) return r.fallback()
      if (url.includes('/api/svc/')) return r.abort()
      return r.fulfill({ status: 200, json: {} })
    })

    let jsBytes = 0
    const pending: Promise<void>[] = []
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.pathname.startsWith('/_next/static/') && url.pathname.endsWith('.js')) {
        pending.push(response.body().then(b => { jsBytes += b.length }, () => undefined))
      }
    })

    await page.goto(route, { waitUntil: 'load' })
    await page.waitForLoadState('networkidle').catch(() => undefined)
    await Promise.all(pending)
    const lcpMs = await page.evaluate(() => new Promise<number>(resolve => {
      let last = 0
      new PerformanceObserver(list => {
        for (const entry of list.getEntries()) last = Math.max(last, entry.startTime)
      }).observe({ type: 'largest-contentful-paint', buffered: true })
      setTimeout(() => resolve(Math.round(last)), 500)
    }))

    measured[route] = { jsBytes, lcpMs }
    test.info().annotations.push({ type: 'perf', description: `${route} js=${jsBytes} lcp=${lcpMs}ms` })
    if (process.env.OPENBANK_PERF_RECORD) return
    expect(jsBytes, `${route}: client JS ${jsBytes} B exceeds budget ${budget.jsBytes} B`).toBeLessThanOrEqual(budget.jsBytes)
    expect(lcpMs, `${route}: LCP ${lcpMs} ms exceeds budget ${budget.lcpMs} ms`).toBeLessThanOrEqual(budget.lcpMs)
  })
}
