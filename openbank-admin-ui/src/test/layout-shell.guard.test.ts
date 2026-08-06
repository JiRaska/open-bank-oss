// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI app-shell rule (enforced) ─────────────────────────────────────
//
// Why this guard exists: the admin-ui wraps every operator page in the app
// shell — the Sidebar (nav) + Header — via a per-route `layout.tsx`. The root
// `app/layout.tsx` only mounts providers (Session/Language), NOT the shell, so
// a page whose route subtree has no shell `layout.tsx` renders bare: no sidebar,
// no header. To an operator that reads as "it opened in a new window, the nav is
// gone" — and it had to be fixed page-by-page (FinOps/DevOps shipped without one).
//
// The rule: every operator page resolves an app-shell layout — a `layout.tsx`
// in its own directory or an ancestor (below `app/`) that renders <Sidebar>.
// This test is the executable form of that rule: it fails CI if a NEW page is
// added without a shell layout in its route subtree, so the footgun can't recur.
//
// If this test flags your page: add a `layout.tsx` to your route dir (copy
// dashboard/layout.tsx) — Sidebar + Header + scrollable <main>. Pages that are
// intentionally shell-less (auth screens, the root redirect) go in EXEMPT.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync, existsSync } from 'fs'
import path from 'path'

const APP_DIR = path.resolve(__dirname, '../app')

// Pages that intentionally render WITHOUT the app shell.
//  - auth/* : pre-login screens (no session, no nav by design)
//  - page.tsx (root): the "/" entry, redirects before any chrome shows
const EXEMPT = new Set<string>([
  'page.tsx',
  'auth/login/page.tsx',
  'auth/error/page.tsx',
  'auth/forbidden/page.tsx',
  'privacy/page.tsx',
])

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (entry === 'page.tsx') out.push(full)
  }
  return out
}

// A shell layout is one that renders the Sidebar. We check the source for a
// <Sidebar reference rather than importing/executing it (the file is a server
// component tree we don't want to render here).
function isShellLayout(layoutPath: string): boolean {
  if (!existsSync(layoutPath)) return false
  return /\bSidebar\b/.test(readFileSync(layoutPath, 'utf8'))
}

// Walk up from the page's directory to (but NOT including) app/, looking for a
// layout.tsx that mounts the shell. app/layout.tsx is excluded on purpose — it
// only provides context, not the Sidebar/Header chrome.
function hasShellLayoutInChain(pageFile: string): boolean {
  let dir = path.dirname(pageFile)
  while (dir.startsWith(APP_DIR) && dir !== APP_DIR) {
    if (isShellLayout(path.join(dir, 'layout.tsx'))) return true
    dir = path.dirname(dir)
  }
  return false
}

describe('admin-ui app-shell rule', () => {
  const pages = walk(APP_DIR)

  it('discovers page files', () => {
    expect(pages.length).toBeGreaterThan(10)
  })

  for (const file of pages) {
    const rel = path.relative(APP_DIR, file).split(path.sep).join('/')
    if (EXEMPT.has(rel)) continue

    it(`${rel} renders inside the app shell (Sidebar + Header)`, () => {
      expect(
        hasShellLayoutInChain(file),
        `${rel} has no app-shell layout in its route subtree — it will render WITHOUT the Sidebar/Header.\n\nAdd a layout.tsx to your route directory (copy src/app/dashboard/layout.tsx):\n  Sidebar + Header + scrollable <main>.\nIf the page is intentionally shell-less (an auth screen), add it to EXEMPT in this test.`,
      ).toBe(true)
    })
  }
})
