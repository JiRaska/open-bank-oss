// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue #3604 — a delegation counterparty must be identifiable by a human, not a truncated UUID.
// The claim under test is exactly that: with a snapshotted name on the grant the chip renders the
// NAME, and it renders it WITHOUT asking any service (which is the whole reason the name is
// snapshotted at offer time — customer-edge has no permitted party-id lookup). Without a name the
// chip must still degrade to its own resolution and then to a shortened id, never to a blank.
//
// The `fetch` assertions are load-bearing, not incidental: a fix that merely passed the id along
// and resolved it client-side would satisfy a text assertion and none of the constraint.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { SessionProvider } from 'next-auth/react'
import { EntityChip } from '@/components/entities/EntityChip'
import { counterpartyLabel, grantCounterparty, type Grant } from '@/components/delegations/GrantView'
import { ROLES } from '@/lib/auth/roles'

const PARTY_ID = '33333333-3333-4333-8333-333333333333'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })))
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('counterpartyLabel', () => {
  it('passes a real name through', () => {
    expect(counterpartyLabel('Alice Testerova')).toBe('Alice Testerova')
  })

  // Null/undefined/blank must all collapse to undefined, which is the ONLY value EntityChip
  // treats as "resolve it yourself". '' would render an empty chip — a name that looks broken
  // rather than one that was never captured.
  it('treats an absent or blank name as no label at all', () => {
    expect(counterpartyLabel(null)).toBeUndefined()
    expect(counterpartyLabel(undefined)).toBeUndefined()
    expect(counterpartyLabel('')).toBeUndefined()
    expect(counterpartyLabel('   ')).toBeUndefined()
  })
})

describe('grantCounterparty', () => {
  const grant = {
    grantorPartyId: 'grantor-id',
    grantorName: 'Owner',
    granteePartyId: 'grantee-id',
    granteeName: 'Delegate',
  } as Grant

  it('shows the recipient for access shared by the selected party', () => {
    expect(grantCounterparty(grant, 'granted')).toEqual({ id: 'grantee-id', name: 'Delegate' })
  })

  it('shows the owner for access shared with the selected party', () => {
    expect(grantCounterparty(grant, 'received')).toEqual({ id: 'grantor-id', name: 'Owner' })
  })
})

describe('delegation counterparty chip', () => {
  const renderChip = (roles: string[], chip: React.ReactElement) => render(
    <SessionProvider session={{ user: { roles } } as never}>{chip}</SessionProvider>,
  )

  it('shows the snapshotted name and asks no service for it', async () => {
    renderChip([ROLES.OPERATOR], <EntityChip type="party" id={PARTY_ID} label={counterpartyLabel('Alice Testerova')} />)

    expect(await screen.findByText('Alice Testerova')).toBeTruthy()
    // The regression this guards: the id must not be what the human reads.
    expect(screen.queryByText('33333333…')).toBeNull()
    expect(vi.mocked(global.fetch)).not.toHaveBeenCalled()
  })

  it('falls back to a shortened id, never a blank, when the grant carries no name', async () => {
    // A grant offered before #3604: party-service answers, but with nothing usable as a label.
    renderChip([ROLES.OPERATOR], <EntityChip type="party" id={PARTY_ID} label={counterpartyLabel(null)} />)

    await waitFor(() => expect(screen.getByText('33333333…')).toBeTruthy())
  })

  it('keeps a party snapshot visible but non-linking for a role denied party PII', () => {
    renderChip([ROLES.COMPLIANCE], <EntityChip type="party" id={PARTY_ID} label={counterpartyLabel('Alice Testerova')} />)
    expect(screen.getByText('Alice Testerova')).toBeTruthy()
    expect(screen.queryByRole('link')).toBeNull()
    expect(vi.mocked(global.fetch)).not.toHaveBeenCalled()
  })
})
