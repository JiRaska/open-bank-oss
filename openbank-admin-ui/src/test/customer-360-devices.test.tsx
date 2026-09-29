// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// An operator on Customer 360 asked to see a party's registered push devices and last-active time
// (there is no login/session tracking anywhere in the fleet — see notification-service DeviceResource).
//
// Same reasoning as customer-360-adverse-state.test.tsx: the failure path is the one worth pinning,
// so an unreachable notification-service never renders as "no devices" — those read identically to
// an operator unless the failure state is explicit. The BFF URL is pinned as a LITERAL, not derived
// from svcUrl(), so the assertion can actually catch the component pointing at a dead route.

import { describe, it, expect, vi, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { DevicesPanel } from '@/components/party/DevicesPanel'

const PARTY = '55555555-5555-5555-5555-555555555555'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPanel() {
  return render(
    <LanguageProvider>
      <DevicesPanel partyId={PARTY} />
    </LanguageProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Customer 360 devices panel', () => {
  it('uses the shared party-filtered graph route', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ devices: [], unavailable: [] })))
    renderPanel()
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    const url = String((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0])
    expect(url).toBe(`/api/customer-360/${PARTY}/graph`)
  })

  it('renders a row per device with status and last-active, never the push token', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      devices: [{
        id: 'd1',
        platform: 'IOS',
        appInstance: 'inst-1',
        appVersion: '2.3.0',
        osVersion: '18.1',
        status: 'ACTIVE',
        registeredAt: '2026-07-17 10:00:00',
        refreshedAt: '2026-08-15 09:30:00',
        lastUsedAt: '2026-08-15 09:30:00',
      }],
      unavailable: [],
    })))
    renderPanel()

    await waitFor(() => expect(screen.getByText('IOS')).toBeTruthy())
    expect(screen.getByText(/2\.3\.0/)).toBeTruthy()
    // The response contract omits `token` entirely, so the JSON value itself can never leak; this
    // guards against a future column accidentally rendering a raw token verbatim on the page.
    expect(screen.queryByText(/^[A-Za-z0-9+/]{40,}$/)).toBeNull()
  })

  it('states an empty result as measured, not unknown', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ devices: [], unavailable: [] })))
    renderPanel()
    await waitFor(() => expect(screen.getByText(/No registered devices/i)).toBeTruthy())
  })

  // THE load-bearing one — same shape as the adverse-state panel's.
  it('never reports an unreachable notification-service as "no devices"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'unauthorized' }, 403)))
    renderPanel()

    await waitFor(() => expect(screen.getByText(/Devices unavailable/i)).toBeTruthy())
    expect(screen.queryByText(/No registered devices/i)).toBeNull()
  })

  it('treats a scaled-to-zero backend the same way — unknown, not empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ devices: [], unavailable: ['devices'] })))
    renderPanel()

    await waitFor(() => expect(screen.getByText(/unreachable/i)).toBeTruthy())
    expect(screen.queryByText(/No registered devices/i)).toBeNull()
  })
})
