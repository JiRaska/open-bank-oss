// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 D4: the cross-deal queue of cash-flow overrides awaiting a second person.
import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, screen, within } from '@testing-library/react'
import { OverrideQueue } from '@/components/treasury/wb/OverrideQueue'
import { json, notServed, renderPage } from './treasury-wb-harness'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const pending = {
  overrideId: 'o-1', dealId: 'd-12345678-aaaa', flowId: 'f-1', status: 'PENDING', paymentDate: '2026-11-16', amount: 1250.5,
  reason: 'counterparty holiday', proposedBy: 'bob.backoffice', proposedByType: 'HUMAN', proposedAt: '2026-10-19T08:00:00Z',
  decidedBy: null, decidedAt: null, decisionReason: null,
}

describe('override queue', () => {
  it('asks for PENDING overrides and links each to its deal', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (u: string) => { calls.push(String(u)); return json([pending, { ...pending, overrideId: 'o-2', proposedBy: 'agent:treasury-assistant', proposedByType: 'AI_AGENT' }]) }))
    await renderPage(<OverrideQueue />)
    const table = await screen.findByTestId('override-queue')
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(within(table).getAllByRole('link')[0].getAttribute('href')).toBe('/treasury/deals/d-12345678-aaaa')
    expect(table.textContent).toMatch(/AI agent/)
    const q = new URL(calls[0], 'http://x')
    expect(q.pathname).toBe('/api/svc/treasury-service/api/v1/treasury/cash-flow-overrides')
    expect(Object.fromEntries(q.searchParams)).toEqual({ status: 'PENDING', limit: '100' })
  })

  it('says so when nothing waits, and when the deployment does not serve the queue', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])))
    await renderPage(<OverrideQueue />)
    expect(await screen.findByTestId('override-queue-empty')).toBeTruthy()
    cleanup()
    vi.stubGlobal('fetch', vi.fn(async () => notServed()))
    await renderPage(<OverrideQueue />)
    expect((await screen.findByTestId('not-available')).textContent).toMatch(/Not yet available on this deployment|Zatím není dostupné/)
  })
})
