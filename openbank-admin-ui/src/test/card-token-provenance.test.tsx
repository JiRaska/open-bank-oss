// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Card Center — network tokens (ADR-0283 phase 3, issue #8811).
//
// THE ONE PROPERTY THIS FILE EXISTS FOR: the same rows must render differently depending on where
// the answer came from. `source: LOCAL_MIRROR` means the network did not answer and these are the
// bank's last recorded rows — a token that says ACTIVE may have been suspended an hour ago. A
// screen that dropped `source` would present that as current, and nothing else on the page can
// tell an operator otherwise.
//
// The two cases below use IDENTICAL token rows on purpose. An assertion about the rows cannot
// separate them; only the provenance banner can.

import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createElement } from 'react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { ROLES } from '@/lib/auth/roles'

let mockRoles: string[] = []
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: mockRoles, accessToken: 'test-token' } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

import CardTokensPage from '@/app/cards/tokens/page'

const CARD_ID = '11111111-2222-4333-8444-555555555555'

const TOKEN = {
  id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
  cardId: CARD_ID,
  tokenReference: 'sim-tok-1',
  requestorId: 'wallet-apple',
  requestorLabel: 'Apple Pay',
  last4: '0000',
  status: 'ACTIVE' as const,
  scheme: 'SIMULATOR',
  expiry: null,
  provisionedAt: '2026-09-05T12:00:00Z',
  updatedAt: '2026-09-05T12:00:00Z',
}

function renderPage() {
  return render(createElement(LanguageProvider, null, createElement(CardTokensPage)))
}

async function loadCard(body: unknown) {
  vi.stubGlobal('fetch', vi.fn(async () =>
    new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }),
  ))
  await act(async () => { renderPage() })
  const input = screen.getByPlaceholderText('00000000-0000-0000-0000-000000000000') as HTMLInputElement
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!.set!
    setter.call(input, CARD_ID)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await act(async () => {
    screen.getByRole('button', { name: /Load/i }).click()
  })
}

describe('Card Center — network token provenance', () => {
  beforeEach(() => { mockRoles = [ROLES.OPERATOR] })
  afterEach(() => { vi.unstubAllGlobals(); cleanup() })

  it('says the network answered when source is NETWORK', async () => {
    await loadCard({ tokens: [TOKEN], source: 'NETWORK', degradedReason: null, count: 1 })

    await waitFor(() => expect(screen.getByText('Apple Pay')).toBeInTheDocument())
    expect(screen.getByText(/The network answered/i)).toBeInTheDocument()
    expect(screen.queryByText(/may be stale/i)).not.toBeInTheDocument()
  })

  it('warns that the same rows may be stale when source is LOCAL_MIRROR', async () => {
    await loadCard({
      tokens: [TOKEN],
      source: 'LOCAL_MIRROR',
      degradedReason: 'UNAVAILABLE from SIMULATOR: connect timeout',
      count: 1,
    })

    await waitFor(() => expect(screen.getByText('Apple Pay')).toBeInTheDocument())
    // Identical rows to the case above; only these two assertions can tell them apart.
    expect(screen.getByText(/did not answer/i)).toBeInTheDocument()
    expect(screen.getByText(/may be stale/i)).toBeInTheDocument()
    // The reason travels too: "the network was unreachable" and "the binding is not configured"
    // need different next steps, and the operator can only distinguish them from the detail.
    expect(screen.getByText(/connect timeout/i)).toBeInTheDocument()
  })
})
