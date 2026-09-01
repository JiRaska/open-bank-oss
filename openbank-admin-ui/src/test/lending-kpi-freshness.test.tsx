// SPDX-License-Identifier: Apache-2.0

// #7918 — the lending KPI tiles rendered an UNCONFIRMED zero as a fact.
//
// `kpi` is derived from `loans`/`applications`, which start as `[]`. So before the first fetch
// resolves, and again after an outage leaves them `[]`, every tile read `0` and the exposure hint
// read `principal 0 CZK`. A credit desk cannot tell "the book is empty" from "we have not asked
// yet" or "lending-service is down" — three states collapsed onto one number, and the error banner
// beside it does not un-assert the tile.
//
// The control that makes these tests mean anything is the THIRD one: a genuinely empty but
// SUCCESSFUL load must still render `0`, not a placeholder. Without it the fix could simply be
// "render a dash whenever the total is zero", which would pass the first two assertions while
// measuring nothing.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import LendingPage from '@/app/lending/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const never = () => new Promise<Response>(() => {})

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const tiles = () => Array.from(document.querySelectorAll('.stat-value')).map(n => n.textContent ?? '')
const hints = () => Array.from(document.querySelectorAll('.stat-hint')).map(n => n.textContent ?? '')

describe('Lending KPI freshness (#7918)', () => {
  it('does not assert a zero exposure before any load has succeeded', async () => {
    vi.stubGlobal('fetch', vi.fn(never))
    render(<LanguageProvider><LendingPage /></LanguageProvider>)

    await waitFor(() => expect(document.querySelectorAll('.stat-value').length).toBeGreaterThan(0))
    // No tile may claim a count, and no hint may claim a principal, while the load is in flight.
    expect(tiles()).not.toContain('0')
    expect(hints().join(' ')).not.toMatch(/principal 0/)
  })

  it('does not assert a zero exposure after the list endpoints fail', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'down' }, 503)))
    render(<LanguageProvider><LendingPage /></LanguageProvider>)

    // The outage banner appearing is necessary but not sufficient — the tiles beside it must not
    // simultaneously report a confident zero book.
    await waitFor(() => expect(screen.getByText(/unreachable/i)).toBeInTheDocument())
    expect(tiles()).not.toContain('0')
    expect(hints().join(' ')).not.toMatch(/principal 0/)
  })

  // THE CONTROL. A successful load that genuinely returns nothing is a real, knowable zero and
  // must still render as `0`. If this fails, the fix is tracking emptiness rather than confirmation.
  it('DOES report zero when the load succeeds and the book is genuinely empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])))
    render(<LanguageProvider><LendingPage /></LanguageProvider>)

    await waitFor(() => expect(tiles()).toContain('0'))
    expect(screen.queryByText(/unreachable/i)).not.toBeInTheDocument()
    expect(hints().join(' ')).toMatch(/principal 0/)
  })
})
