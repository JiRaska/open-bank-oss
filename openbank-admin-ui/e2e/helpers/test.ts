// SPDX-License-Identifier: Apache-2.0
//
// ADR-0370 U1 — the `test` every e2e spec imports. Identical to `@playwright/test` plus one
// auto fixture: every `route.fulfill()` a spec installs through `page.route()` or
// `context.route()` is checked against the target service's openapi.yaml when the request is
// a generic `/api/svc/<service>/...` proxy call. A violation not in `contract-baseline.json`
// fails the test after it ran.
//
// Set OPENBANK_CONTRACT_RECORD=<file> to append violations to that file as JSON lines instead
// of failing — the only supported way to (re)generate the baseline.

import { appendFileSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test as base, expect, type BrowserContext, type Page, type Route } from '@playwright/test'
import { OpenApiContract, violationKey, type Violation } from './openapi-contract'

export * from '@playwright/test'

const E2E_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = path.resolve(E2E_DIR, '../..')
const BASELINE_FILE = path.join(E2E_DIR, 'contract-baseline.json')

const contract = new OpenApiContract(REPO_ROOT)
const baseline = new Set<string>(JSON.parse(readFileSync(BASELINE_FILE, 'utf8')).violations as string[])

type FulfillOptions = Parameters<Route['fulfill']>[0]

function mockedBody(options: FulfillOptions): unknown {
  if (!options) return undefined
  if ('json' in options && options.json !== undefined) return options.json
  if (typeof options.body === 'string') return options.body
  if (options.body instanceof Buffer) return options.body.toString('utf8')
  return undefined
}

function guardRoute(route: Route, found: Violation[]): Route {
  return new Proxy(route, {
    get(target, prop, receiver) {
      if (prop !== 'fulfill') return Reflect.get(target, prop, receiver)
      return async (options: FulfillOptions = {}) => {
        // A fulfil from a real fetched response carries the real shape — nothing to check.
        if (!options.response && !options.path) {
          const request = target.request()
          const violations = contract.check({
            method: request.method(),
            url: request.url(),
            status: options.status ?? 200,
            body: mockedBody(options),
          })
          if (violations) found.push(...violations)
        }
        return target.fulfill(options)
      }
    },
  })
}

type RouteHost = Pick<Page, 'route'> | Pick<BrowserContext, 'route'>

function wrap(host: RouteHost, found: Violation[]): void {
  const original = host.route.bind(host) as unknown as (...a: unknown[]) => Promise<void>
  ;(host as { route: unknown }).route = (url: unknown, handler: unknown, options?: unknown) => {
    if (typeof handler !== 'function') return original(url, handler, options)
    const h = handler as (route: Route, ...rest: unknown[]) => unknown
    return original(url, (route: Route, ...rest: unknown[]) => h(guardRoute(route, found), ...rest), options)
  }
}

// The guard wraps the `page` and `context` fixtures rather than depending on them: an auto fixture
// that asked for `page` would launch a browser for request-only specs (the deployed auth-gate
// lane runs with no browser installed). `contractViolations` has no dependencies, is torn down
// after `page` and `context`, and so sees every fulfil of the test before it asserts.
export const test = base.extend<{ contractViolations: Violation[] }>({
  contractViolations: [
    async ({}, use, testInfo) => {
      const found: Violation[] = []
      await use(found)
      const specFile = path.relative(E2E_DIR, testInfo.file)
      const fresh = [...new Map(found.map(v => [violationKey(specFile, v), v])).entries()]
        .filter(([key]) => !baseline.has(key))
      if (fresh.length === 0) return
      const record = process.env.OPENBANK_CONTRACT_RECORD
      if (record) {
        for (const [key] of fresh) appendFileSync(record, `${JSON.stringify(key)}\n`)
        return
      }
      const lines = fresh.map(([key, v]) => `  ${key}\n    ${v.detail}`).join('\n')
      throw new Error(
        `Mocked /api/svc responses disagree with the service openapi.yaml (ADR-0370 U1):\n${lines}\n` +
          'Fix the mock (or the spec, if the service really returns this). The baseline only shrinks.',
      )
    },
    { auto: true },
  ],
  context: async ({ context, contractViolations }, provide) => {
    wrap(context, contractViolations)
    await provide(context)
  },
  page: async ({ page, contractViolations }, provide) => {
    wrap(page, contractViolations)
    await provide(page)
  },
})

export { expect }
