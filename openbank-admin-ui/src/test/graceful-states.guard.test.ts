// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI graceful-state rule (enforced) ────────────────────────────────
//
// Why this guard exists: nearly every page fetches from the BFF (`/api/svc/...`)
// or an admin-ui-internal API. In the sandbox most of the 28-service fleet isn't
// deployed, so those calls legitimately fail. Historically each page re-invented
// its own error rendering — a raw `HTTP 404`, a hand-written "Cannot reach X", a
// red `alert-error` box — which an operator reads as "the app is broken", and
// which had to be fixed page-by-page, again and again.
//
// The rule: a page NEVER renders a raw backend failure. It classifies BFF
// failures with `classifyBffFailure()` and renders the shared `<DataUnavailable>`
// panel (or a soft `{available:false}` envelope from an internal route). This
// test is the executable form of that rule — it fails CI if a page reintroduces
// one of the banned raw-error anti-patterns, so every NEW page must comply too.
//
// If this test flags your page: replace the raw error with `<DataUnavailable>`.
// See src/components/feedback/DataUnavailable.tsx and src/lib/services/bff.ts.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import path from 'path'

const APP_DIR = path.resolve(__dirname, '../app')

// Auth screens are static (no BFF fetch) and intentionally show their own copy.
const EXEMPT = new Set<string>([
  'auth/login/page.tsx',
  'auth/error/page.tsx',
  'auth/forbidden/page.tsx',
])

// Each pattern is a raw-error anti-pattern that leaks a backend failure to the
// operator instead of degrading through <DataUnavailable>.
const BANNED: { re: RegExp; why: string }[] = [
  { re: /`HTTP \$\{/, why: 'constructs a raw "HTTP ${status}" string in a page — classify with classifyBffFailure() and render <DataUnavailable> instead' },
  { re: /className=["'][^"']*\balert-error\b/, why: 'renders a raw alert-error box — use <DataUnavailable kind=…> instead' },
  { re: /[Cc]annot reach|[Cc]ould not reach|[Uu]nable to reach/, why: 'hand-written "cannot reach" copy — use <DataUnavailable kind="unreachable"|"not_deployed"> so the message is consistent and localized' },
]

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (entry === 'page.tsx') out.push(full)
  }
  return out
}

// Strip line + block comments so an explanatory comment that mentions a banned
// phrase doesn't trip the guard — only real rendered/thrown code is checked.
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('admin-ui graceful-state rule', () => {
  const pages = walk(APP_DIR)

  it('discovers page files', () => {
    expect(pages.length).toBeGreaterThan(10)
  })

  for (const file of pages) {
    const rel = path.relative(APP_DIR, file).split(path.sep).join('/')
    if (EXEMPT.has(rel)) continue

    it(`${rel} does not leak a raw backend failure`, () => {
      const code = stripComments(readFileSync(file, 'utf8'))
      const hits = BANNED.filter(b => b.re.test(code)).map(b => b.why)
      expect(hits, `${rel} violates the graceful-state rule:\n  - ${hits.join('\n  - ')}\n\nRoute the failure through <DataUnavailable> (src/components/feedback/DataUnavailable.tsx).`).toEqual([])
    })
  }
})
