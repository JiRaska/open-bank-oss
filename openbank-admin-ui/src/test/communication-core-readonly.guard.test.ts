// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── ADR-0285 D1 guard: the prompt core is never writable through any UI path ───────────────
//
// D1 says the core — safety rules, injection defence, tool routing, HITL/SCA — is owned by
// engineering in git and "there is no endpoint that writes it". A prose decision is not a control:
// the cheapest way for that to stop being true is someone adding a POST/PUT/PATCH/DELETE handler
// to the Communication Studio routes during phase 2, when a write path legitimately appears for
// the STYLE layer and it is one file away from also carrying a core write.
//
// This test is the executable form of the rule. It fails on a write verb under
// /api/communication, and on a page under /communication that submits a form to one. When phase 2
// lands the style write path it will live in openbank-communication-service behind
// `commstyle.publish` four-eyes, not in an admin-ui route handler — so this guard should still
// hold. If a future change genuinely needs a write here, that is a decision to record in the ADR,
// not a line to delete from a test.
//
// Known-negative discipline (repo rule: a guard is proven by what it rejects): the detector is
// exercised against a synthetic source string carrying `export async function POST` below, so a
// regex that stopped matching would fail this file rather than silently passing the real tree.

import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'

const API_DIR = path.resolve(__dirname, '../app/api/communication')
const PAGE_DIR = path.resolve(__dirname, '../app/communication')

const WRITE_HANDLER = /export\s+(?:async\s+)?function\s+(POST|PUT|PATCH|DELETE)\b/

function filesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap(entry => {
    const full = path.join(dir, entry)
    return statSync(full).isDirectory() ? filesUnder(full) : [full]
  })
}

describe('ADR-0285 D1 — the prompt core is read-only', () => {
  it('detects a write handler (known-positive: the regex still matches)', () => {
    expect(WRITE_HANDLER.test('export async function POST(request: Request) {}')).toBe(true)
    expect(WRITE_HANDLER.test('export async function GET() {}')).toBe(false)
  })

  it('exposes no write verb under /api/communication', () => {
    const offenders = filesUnder(API_DIR).filter(file => WRITE_HANDLER.test(readFileSync(file, 'utf8')))

    expect(offenders).toEqual([])
  })

  it('renders no editing control on the Communication Studio pages', () => {
    // A <form>, a <textarea> or a contentEditable region on these pages would mean the read-only
    // projection has grown an editor without the service, the linter and the four-eyes step that
    // ADR-0285 D3/D4 require around one.
    const offenders = filesUnder(PAGE_DIR)
      .filter(file => file.endsWith('.tsx'))
      .filter(file => /<form\b|<textarea\b|contentEditable/i.test(readFileSync(file, 'utf8')))

    expect(offenders).toEqual([])
  })
})
