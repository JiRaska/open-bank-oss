// SPDX-License-Identifier: Apache-2.0

import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { EffectiveAccess, type EffectiveAccessPayload } from '@/components/delegations/EffectiveAccess'
import { CAPABILITIES_BY_RESOURCE } from '@/lib/delegations/rolePresets'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

describe('effective access authority source', () => {
  it('partitions owned resources, delegated cards, and delegated grants per card', () => {
    renderEffectiveAccess()

    const owned = within(screen.getByRole('region', { name: 'Owned resources' }))
    const owner = within(owned.getByRole('link', { name: 'Account owner: Owned account — Derived from product record' }))
    expect(owner.getByText('Derived from product record')).toBeVisible()
    expect(owner.getByLabelText('Effective rights')).toContainElement(owner.getByTitle('DELEGATION_MANAGE'))
    expect(owned.getByRole('link', { name: 'Card owner: •••• 1111 — Derived from product record' })).toBeVisible()
    expect(owned.queryByRole('link', { name: /Additional cardholder: •••• 2222/ })).not.toBeInTheDocument()

    const delegatedCards = within(screen.getByRole('region', { name: 'Delegated cards' }))
    const delegatedCard = within(delegatedCards.getByRole('link', { name: 'Additional cardholder: •••• 2222 — Derived from cardholder record' }))
    expect(delegatedCard.getByText('Derived from cardholder record')).toBeVisible()
    expect(delegatedCard.queryByLabelText('Effective rights')).not.toBeInTheDocument()
    const evidence = within(delegatedCard.getByRole('note', { name: 'Cardholder authority evidence' }))
    expect(evidence.getByText('Authority derives from the cardholder record')).toBeVisible()
    expect(evidence.getByText(/Linked delegation: grant-re…/)).toBeVisible()
    expect(evidence.getByText(/No card capabilities are inferred here from that account delegation/)).toBeVisible()
    expect(evidence.getByText(/Separate card-scoped grants are listed below/)).toBeVisible()
    for (const capability of [...CAPABILITIES_BY_RESOURCE.CARD, ...CAPABILITIES_BY_RESOURCE.ACCOUNT]) {
      expect(delegatedCard.queryByTitle(capability)).not.toBeInTheDocument()
    }
    expect(delegatedCard.queryAllByTitle(/^(CARD|ACCOUNT)_/)).toHaveLength(0)
    expect(delegatedCards.queryByRole('link', { name: /Card owner: •••• 1111/ })).not.toBeInTheDocument()

    const delegatedRights = within(screen.getByRole('region', { name: 'Delegated rights effective now' }))
    const ordinaryGrant = within(delegatedRights.getByRole('link', { name: 'Account reader: Shared account — Granted by delegation' }))
    expect(ordinaryGrant.getByText('Granted by delegation')).toBeVisible()
    const cardGrant = within(delegatedRights.getByRole('link', { name: 'Card viewer: •••• 2222 — Granted by delegation' }))
    expect(cardGrant.getByLabelText('Effective rights')).toContainElement(cardGrant.getByTitle('CARD_VIEW'))
  })

  it('renders legacy delegation management as evidence rather than effective authority', () => {
    renderEffectiveAccess()

    const delegatedRights = within(screen.getByRole('region', { name: 'Delegated rights effective now' }))
    const legacyGrant = within(delegatedRights.getByRole('link', { name: 'Historical delegated rights: Legacy account — Granted by delegation — historical evidence, not effective authority' }))
    const effectiveRights = within(legacyGrant.getByLabelText('Effective rights'))
    expect(effectiveRights.getByTitle('ACCOUNT_READ_BALANCES')).toBeVisible()
    expect(effectiveRights.queryByTitle('DELEGATION_MANAGE')).not.toBeInTheDocument()

    const legacyEvidence = within(legacyGrant.getByRole('note', { name: 'Legacy unsupported capabilities' }))
    expect(legacyEvidence.getByText('Historical evidence only — not effective authority')).toBeVisible()
    expect(legacyEvidence.getByTitle('DELEGATION_MANAGE')).toHaveTextContent('Delegates')
  })
})

function renderEffectiveAccess() {
  const data: EffectiveAccessPayload = {
    evaluatedAt: '2026-09-01T12:00:00Z',
    nextChangeAt: null,
    refreshAfterMs: null,
    accounts: [{ id: 'account-owned', nickname: 'Owned account' }],
    cards: [
      { id: 'card-owned', maskedPan: '•••• 1111', delegated: false },
      { id: 'card-delegated', maskedPan: '•••• 2222', delegated: true, delegationGrantId: 'grant-received' },
    ],
    grants: [
      {
        id: 'grant-received',
        grantorPartyId: 'grantor-party',
        granteePartyId: 'selected-party',
        resourceType: 'ACCOUNT',
        resourceId: 'account-shared',
        capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_INITIATE_PAYMENT'],
        validFrom: '2026-08-01T00:00:00Z',
        validTo: null,
        status: 'ACTIVE',
      },
      {
        id: 'grant-legacy',
        grantorPartyId: 'legacy-grantor',
        granteePartyId: 'selected-party',
        resourceType: 'ACCOUNT',
        resourceId: 'account-legacy',
        capabilities: ['ACCOUNT_READ_BALANCES', 'DELEGATION_MANAGE'],
        validFrom: '2026-08-01T00:00:00Z',
        validTo: null,
        status: 'ACTIVE',
      },
      {
        id: 'grant-card',
        grantorPartyId: 'card-grantor',
        granteePartyId: 'selected-party',
        resourceType: 'CARD',
        resourceId: 'card-delegated',
        capabilities: ['CARD_VIEW'],
        validFrom: '2026-08-01T00:00:00Z',
        validTo: null,
        status: 'ACTIVE',
      },
    ],
    presets: [
      {
        id: 'read-account',
        name: 'Account reader',
        description: '',
        resourceType: 'ACCOUNT',
        capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_INITIATE_PAYMENT'],
      },
      {
        id: 'legacy-owner',
        name: 'Account owner',
        description: '',
        resourceType: 'ACCOUNT',
        capabilities: ['ACCOUNT_READ_BALANCES', 'DELEGATION_MANAGE'],
      },
      {
        id: 'card-viewer',
        name: 'Card viewer',
        description: '',
        resourceType: 'CARD',
        capabilities: ['CARD_VIEW'],
      },
    ],
    resourceDetails: [
      {
        key: 'ACCOUNT:account-shared',
        resourceType: 'ACCOUNT',
        resourceId: 'account-shared',
        state: 'ok',
        detail: { id: 'account-shared', nickname: 'Shared account' },
      },
      {
        key: 'ACCOUNT:account-legacy',
        resourceType: 'ACCOUNT',
        resourceId: 'account-legacy',
        state: 'ok',
        detail: { id: 'account-legacy', nickname: 'Legacy account' },
      },
      {
        key: 'CARD:card-delegated',
        resourceType: 'CARD',
        resourceId: 'card-delegated',
        state: 'ok',
        detail: { id: 'card-delegated', maskedPan: '•••• 2222', delegated: true },
      },
    ],
    sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
  }

  render(
    <LanguageProvider>
      <EffectiveAccess data={data} />
    </LanguageProvider>,
  )
}
