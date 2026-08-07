// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI render-smoke rule (enforced) ──────────────────────────────────
//
// Why this test exists: until it was added, NOT ONE of the console's ~81 pages
// was ever rendered by the test suite. Every admin-ui test was either a route/lib
// unit test or a `readFileSync`+regex guard scanning source TEXT — nothing was
// mounted. A page could throw on its very first render (a bad import, a hook
// called conditionally, a destructure of an undefined context) and the whole
// suite stayed green. The only thing standing between that and an operator
// seeing a blank screen was someone clicking the page by hand.
//
// The rule: EVERY page under src/app mounts without throwing. That is a low bar
// on purpose — this is a smoke test, not a behavioural one. It answers "does it
// mount", never "does it show the right data". It is the cheapest possible net
// under 81 pages, and it catches the whole class of import/hook/context crashes.
//
// How it works:
//   - Pages are discovered with `import.meta.glob` over src/app/**/page.tsx, so a
//     NEW page is smoke-tested automatically the moment it lands. A hardcoded list
//     would rot on the first PR; this cannot.
//   - Client pages ('use client') mount inside the REAL providers the real root
//     layout uses — LanguageProvider + SessionProvider. Wrapping in the real
//     providers (rather than mocking useLanguage) is deliberate: it proves the
//     actual context wiring, so a page that reads a context key the provider does
//     not supply fails here instead of in production.
//   - Server pages (async RSCs) are invoked and awaited, then their returned tree
//     is rendered — a real render, not an import check.
//   - The network is stubbed (see mockFetch): the point is "does it mount", not
//     "does the backend answer". Pages must already degrade gracefully on a failed
//     fetch — that is the separate graceful-states guard's rule.
//
// If this test fails for your page: it throws on mount. Fix the page — do not add
// it to a skip list. There is no broad escape hatch here by design; see
// CANNOT_MOUNT below for the two narrowly-documented structural exceptions.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, cleanup, act } from '@testing-library/react'
import { readFileSync } from 'fs'
import path from 'path'

// ── Next.js runtime shims ──────────────────────────────────────────────────
// A page renders under the Next runtime, which jsdom does not provide. These
// mocks stand in for the router/headers plumbing ONLY — never for app code, and
// never for the i18n/session contexts (those are the real thing, see below).

// `redirect()` throws a control-flow signal in real Next; a page whose entire body
// is `redirect('/dashboard')` (e.g. src/app/page.tsx) is CORRECT to never return a
// tree. We mirror that contract with a sentinel and count it as a clean mount.
const REDIRECT_SENTINEL = 'NEXT_REDIRECT_SMOKE'
vi.mock('next/navigation', () => ({
  redirect: (url: string) => {
    const e = new Error(REDIRECT_SENTINEL)
    ;(e as Error & { url?: string }).url = url
    throw e
  },
  notFound: () => {
    const e = new Error(REDIRECT_SENTINEL)
    throw e
  },
  useRouter: () => ({
    push: vi.fn(), replace: vi.fn(), refresh: vi.fn(),
    back: vi.fn(), forward: vi.fn(), prefetch: vi.fn(),
  }),
  // Dynamic segments across the app: [id], [name], [slug], [service], [agentId],
  // [[...slug]]. One superset record satisfies every page's useParams() shape.
  useParams: () => SMOKE_PARAMS,
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/',
}))

vi.mock('next/headers', () => ({
  cookies: async () => ({ get: () => undefined, getAll: () => [], has: () => false }),
  headers: async () => new Headers(),
}))

// Superset of every dynamic segment name in src/app. Values are inert fixtures —
// the stubbed BFF answers the same way regardless.
const SMOKE_PARAMS: Record<string, string | string[]> = {
  id: 'smoke-id',
  name: 'ledger-service',
  service: 'ledger-service',
  agentId: 'smoke-agent',
  slug: [],
}

// ── Structural non-mounts (TIGHT — justify every entry in the PR body) ──────
// This is NOT a "page is broken, skip it" list. An entry here means the module is
// structurally not a mountable page component, so "does it mount" is not a
// meaningful question. Anything else belongs in the suite.
const CANNOT_MOUNT = new Map<string, string>([
  // (empty — every page under src/app mounts. Keep it that way.)
])

// ── Page discovery ─────────────────────────────────────────────────────────
// import.meta.glob is Vite-native and compile-time: a new page.tsx is picked up
// automatically, with no list to maintain.
const PAGE_MODULES = import.meta.glob<{ default?: unknown }>('../app/**/page.tsx')
const APP_DIR = path.resolve(__dirname, '../app')

/** '../app/dashboard/page.tsx' -> 'dashboard/page.tsx' */
function relOf(globKey: string): string {
  return globKey.replace(/^\.\.\/app\//, '')
}

/** A page is a client component iff it carries the 'use client' directive. */
function isClientPage(rel: string): boolean {
  const src = readFileSync(path.join(APP_DIR, rel), 'utf8')
  return /^\s*['"]use client['"]/m.test(src)
}

// ── Providers: the REAL ones, exactly as src/app/layout.tsx composes them ───
// Imported lazily inside the wrapper so the vi.mock calls above are hoisted first.
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(
    SessionProvider,
    null,
    React.createElement(LanguageProvider, null, children),
  )
}

// ── Network stub ───────────────────────────────────────────────────────────
// Every BFF call fails, exactly as it does in the sandbox where most of the fleet
// isn't deployed. This is deliberately the HOSTILE case, and it is the honest one:
// "backend unreachable" is a state every page is already required to degrade
// through (<DataUnavailable> — see graceful-states.guard.test.ts), so a page that
// cannot survive it has a real bug. Returning cheerful fake payloads instead would
// mean inventing a response shape per endpoint, which tests the fixture rather
// than the page.
function mockFetch() {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    // next-auth's SessionProvider polls this on mount and does NOT tolerate a
    // rejection; `null` = anonymous, a state every page must render (AuthGuard
    // does the gating).
    if (url.includes('/api/auth/session')) {
      return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    }
    throw new TypeError('fetch failed (render-smoke: BFF unreachable)')
  })
}

// ── Why these mounts get a longer timeout than vitest's 5 s default ────────
// This is a SMOKE test: it asserts "does it mount", never "does it mount fast".
// A latency assertion is not part of its contract, and vitest's default 5 s was
// acting as one. Measured 2026-07-25 (#2235): running this file ALONE is 5/5
// green, but as part of the full 39-file suite 7 of 12 consecutive runs failed
// with `Test timed out in 5000ms` — never on an assertion. The docs pages are the
// heaviest mounts in the suite (mermaid + react-syntax-highlighter + long
// markdown), so under shared-vitest-pool contention they cross 5 s first. The
// same contention is already recorded in generate-governance.test.ts.
//
// The cost of a flake here is not a lost minute — it is that a timeout is
// INDISTINGUISHABLE from a throw, which is the one signal this file exists to
// carry. A ~58%-failing file trains people to re-run rather than read, and the
// next real docs-page mount regression reads as "the flaky one again".
//
// So: keep the timeout generous. Do NOT tighten it back toward 5 s to "catch slow
// pages" — that is a different test, and it would be paid for with the smoke
// signal. If a page genuinely hangs, 30 s still fails it well inside the job.
const MOUNT_TIMEOUT_MS = 30_000

describe('admin-ui render-smoke — every page mounts', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch())
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('discovers the page tree', () => {
    // Tripwire on the vitest `include` glob and on import.meta.glob itself: if
    // either silently stops matching, this fails loudly rather than reporting a
    // vacuous green from zero collected pages.
    expect(Object.keys(PAGE_MODULES).length).toBeGreaterThan(50)
  })

  for (const [globKey, importPage] of Object.entries(PAGE_MODULES)) {
    const rel = relOf(globKey)

    if (CANNOT_MOUNT.has(rel)) {
      it.skip(`${rel} — not mountable: ${CANNOT_MOUNT.get(rel)}`, () => {})
      continue
    }

    it(`${rel} mounts without throwing`, async () => {
      const mod = await importPage()
      const Page = mod.default as
        | ((props: Record<string, unknown>) => React.ReactNode | Promise<React.ReactNode>)
        | undefined

      expect(Page, `${rel} has no default export — a page module must default-export a component.`).toBeTypeOf('function')

      // Next 15+ passes params/searchParams as promises to server components.
      const props = {
        params: Promise.resolve(SMOKE_PARAMS),
        searchParams: Promise.resolve({}),
      }

      try {
        if (isClientPage(rel)) {
          // Awaited act: a client page reading use(params) suspends inside render()'s
          // synchronous act scope and never resumes otherwise — the #3512 flake class the
          // setup.ts gate now fails on. lending/applications/[id] is that page today.
          await act(async () => {
            render(React.createElement(Providers, null, React.createElement(Page!, props)))
          })
        } else {
          // Async RSC: invoke it, await the tree, then really render it.
          const tree = await Page!(props)
          await act(async () => {
            render(React.createElement(Providers, null, tree as React.ReactNode))
          })
        }
      } catch (err) {
        // A page that redirects/notFounds instead of returning a tree did its job.
        if (err instanceof Error && err.message === REDIRECT_SENTINEL) return
        throw err
      }
    }, MOUNT_TIMEOUT_MS)
  }
})
