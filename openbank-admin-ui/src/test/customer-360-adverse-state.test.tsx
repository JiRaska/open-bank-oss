// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue #4265 — an operator on Customer 360 must be able to see that a party is suppressed from
// marketing, and must never be shown an all-clear the console did not actually measure.
//
// The second half is the one worth a test. "No active exclusion" and "we could not reach
// engagement-service" both end up as an absence of badges if the failure path is written lazily,
// and an operator cannot tell them apart — which is worse than showing nothing, because the safe
// reading and the dangerous reading render identically. So this asserts the UNAVAILABLE case
// explicitly, not just the happy one.
//
// It also pins the BFF URL as a LITERAL rather than deriving it from svcUrl(): deriving both the
// expectation and the request from the same helper is vacuous — they move together, and the
// assertion would survive the component pointing at a route that does not exist.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { AdverseStatePanel } from '@/components/party/AdverseStatePanel'

const PARTY = '44444444-4444-4444-4444-444444444444'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPanel() {
  return render(
    <LanguageProvider>
      <AdverseStatePanel partyId={PARTY} />
    </LanguageProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Customer 360 adverse-state panel', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ partyId: PARTY, adverseStates: [] })))
  })

  it('asks engagement-service through the BFF proxy, on the path the service serves', async () => {
    renderPanel()
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    const url = String((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0])
    expect(url).toBe(`/api/svc/engagement-service/api/v1/eligibility/adverse-states?partyId=${PARTY}`)
  })

  it('renders a badge per active state, and only for states the service returned', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ partyId: PARTY, adverseStates: ['ARREARS', 'FRAUD_HOLD'] })))
    renderPanel()

    await waitFor(() => expect(screen.getByTestId('adverse-FRAUD_HOLD')).toBeTruthy())
    expect(screen.getByTestId('adverse-ARREARS')).toBeTruthy()
    // DISPUTE_OPENED was not returned, so no badge exists for it. A permanently dark badge for a
    // declared-but-unwired enum value is indistinguishable from "no open dispute" — the exact
    // ambiguity the issue thread flags.
    expect(screen.queryByTestId('adverse-DISPUTE_OPENED')).toBeNull()
  })

  it('states an empty result as a measured all-clear', async () => {
    renderPanel()
    await waitFor(() => expect(screen.getByText(/No active exclusion/i)).toBeTruthy())
  })

  // THE load-bearing one. The proxy answers 404 `{error:"Unknown service: …"}` when the service is
  // not deployed or not discovered — a state much of the fleet is in per environment.
  it('never reports an unreachable service as "no exclusion"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'Unknown service: engagement-service' }, 404)))
    renderPanel()

    await waitFor(() => expect(screen.getByText(/State unavailable/i)).toBeTruthy())
    expect(screen.queryByText(/No active exclusion/i)).toBeNull()
    expect(screen.getByText(/NOT a confirmation/i)).toBeTruthy()
  })

  it('treats a scaled-to-zero backend the same way — unknown, not clear', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'scaled_to_zero' }, 503)))
    renderPanel()

    await waitFor(() => expect(screen.getByText(/scaled_to_zero/i)).toBeTruthy())
    expect(screen.queryByText(/No active exclusion/i)).toBeNull()
  })
})
