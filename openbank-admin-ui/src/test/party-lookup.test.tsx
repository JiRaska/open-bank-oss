// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// #5904 — "Resolve KYC customers by name/company/UUID through party-service".
// The load-bearing assertions are the negative ones: a lookup that FAILED must render
// differently from a lookup that legitimately found nothing.

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { resolveParty, isUuid, partyLabel } from '@/lib/party/resolveParty'
import { PartyLookup } from '@/components/kyc/PartyLookup'

const UUID = '3f2504e0-4f89-11d3-9a0c-0305e82c3301'

function res(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('resolveParty — party-service lookup', () => {
  it('classifies a UUID apart from a name term', () => {
    expect(isUuid(UUID)).toBe(true)
    expect(isUuid('Novak')).toBe(false)
  })

  it('resolves a known UUID through GET /api/v1/parties/{id}', async () => {
    const fetcher = vi.fn(async () => res(200, { id: UUID, legalName: 'Jan Novák', kycStatus: 'VERIFIED' }))
    const out = await resolveParty(UUID, fetcher as unknown as typeof fetch)

    expect(out).toEqual({
      status: 'ok',
      mode: 'uuid',
      matches: [{ id: UUID, legalName: 'Jan Novák', tradingName: null, kycStatus: 'VERIFIED', status: null }],
    })
    expect(String(fetcher.mock.calls[0][0])).toBe(`/api/svc/party-service/api/v1/parties/${UUID}`)
  })

  it('resolves a company name through GET /api/v1/parties/search?q=', async () => {
    const fetcher = vi.fn(async () => res(200, {
      data: [{ id: UUID, legalName: 'Acme s.r.o.', tradingName: 'Acme' }],
      pagination: { limit: 20, hasNextPage: false },
    }))
    const out = await resolveParty('Acme', fetcher as unknown as typeof fetch)

    expect(out.status).toBe('ok')
    if (out.status !== 'ok') throw new Error('unreachable')
    expect(out.mode).toBe('term')
    expect(partyLabel(out.matches[0])).toBe('Acme')
    // The path is asserted as a LITERAL, not derived from the module under test.
    expect(String(fetcher.mock.calls[0][0])).toBe('/api/svc/party-service/api/v1/parties/search?q=Acme&limit=20')
  })

  it('reports a real empty search result as `none`', async () => {
    const fetcher = vi.fn(async () => res(200, { data: [], pagination: { limit: 20, hasNextPage: false } }))
    expect(await resolveParty('Zzzz', fetcher as unknown as typeof fetch)).toEqual({ status: 'none', mode: 'term' })
  })

  it('reports a 404 from /search as a FAILED lookup, never as `none`', async () => {
    // party-service answers 404 when the party-search feature flag is off
    // (FeatureDisabledMapper). A genuine no-match is a 200 with an empty page, so a 404
    // here can only mean the capability is absent.
    const fetcher = vi.fn(async () => res(404, { code: 'NOT_FOUND', message: "feature 'party-search' is not enabled" }))
    expect(await resolveParty('Novak', fetcher as unknown as typeof fetch))
      .toEqual({ status: 'failed', mode: 'term', reason: 'search_unavailable' })
  })

  it('reports a 404 for a UUID as `none` — that endpoint is not flag-gated', async () => {
    const fetcher = vi.fn(async () => res(404, { code: 'NOT_FOUND', message: 'party not found' }))
    expect(await resolveParty(UUID, fetcher as unknown as typeof fetch)).toEqual({ status: 'none', mode: 'uuid' })
  })

  it('maps the BFF failure vocabulary to failed, not to none', async () => {
    const cases: [number, unknown, string][] = [
      [404, { error: 'Unknown service: party-service' }, 'not_deployed'],
      [503, { error: 'scaled_to_zero' }, 'scaled_to_zero'],
      [401, { error: 'unauthorized' }, 'unauthorized'],
      [502, { error: 'upstream_unreachable' }, 'unreachable'],
    ]
    for (const [status, body, reason] of cases) {
      const out = await resolveParty('Novak', (async () => res(status, body)) as unknown as typeof fetch)
      expect(out).toEqual({ status: 'failed', mode: 'term', reason })
    }
  })

  it('treats a thrown fetch (timeout/network) as failed', async () => {
    const fetcher = vi.fn(async () => { throw new Error('aborted') })
    expect(await resolveParty('Novak', fetcher as unknown as typeof fetch))
      .toEqual({ status: 'failed', mode: 'term', reason: 'unreachable' })
  })

  it('refuses a sub-2-character term instead of asking for a vacuous empty page', async () => {
    const fetcher = vi.fn()
    expect(await resolveParty('N', fetcher as unknown as typeof fetch)).toEqual({ status: 'too_short' })
    expect(fetcher).not.toHaveBeenCalled()
  })
})

describe('PartyLookup — the three rendered states are distinguishable', () => {
  async function runWith(resolution: Awaited<ReturnType<typeof resolveParty>>) {
    const onSelect = vi.fn()
    const { unmount } = render(<PartyLookup onSelect={onSelect} resolve={async () => resolution} />)
    await userEvent.type(screen.getByRole('textbox'), 'Novak')
    await userEvent.click(screen.getByRole('button', { name: /find customer/i }))
    const el = await screen.findByTestId('lookup-state')
    const out = { state: el.getAttribute('data-state'), text: el.textContent ?? '', onSelect }
    unmount()
    return out
  }

  it('renders matches and hands the party id back on click', async () => {
    const onSelect = vi.fn()
    render(<PartyLookup onSelect={onSelect} resolve={async () => ({
      status: 'ok', mode: 'term',
      matches: [{ id: UUID, legalName: 'Jan Novák', tradingName: null, kycStatus: 'VERIFIED', status: null }],
    })} />)
    await userEvent.type(screen.getByRole('textbox'), 'Novak')
    await userEvent.click(screen.getByRole('button', { name: /find customer/i }))
    await userEvent.click(await screen.findByTestId('party-match'))
    expect(onSelect).toHaveBeenCalledWith(UUID)
  })

  it('a legitimate no-match and a failed lookup do not render the same', async () => {
    const none = await runWith({ status: 'none', mode: 'term' })
    const failed = await runWith({ status: 'failed', mode: 'term', reason: 'search_unavailable' })

    expect(none.state).toBe('none')
    expect(failed.state).toBe('failed')
    expect(none.state).not.toBe(failed.state)
    expect(none.text).not.toBe(failed.text)
    // The failed copy must say the emptiness is not evidence.
    expect(failed.text).toMatch(/NOT evidence/i)
    expect(none.text).not.toMatch(/NOT evidence/i)
  })

  it('shows a loading state while the lookup is in flight', async () => {
    let release: (v: Awaited<ReturnType<typeof resolveParty>>) => void = () => {}
    const pending = new Promise<Awaited<ReturnType<typeof resolveParty>>>(r => { release = r })
    render(<PartyLookup onSelect={vi.fn()} resolve={() => pending} />)
    await userEvent.type(screen.getByRole('textbox'), 'Novak')
    await userEvent.click(screen.getByRole('button', { name: /find customer/i }))

    expect((await screen.findByTestId('lookup-state')).getAttribute('data-state')).toBe('loading')
    release({ status: 'none', mode: 'term' })
    await waitFor(async () =>
      expect((await screen.findByTestId('lookup-state')).getAttribute('data-state')).toBe('none'))
  })
})
