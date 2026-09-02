// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { delegationAttentionReasons, effectiveResourceDetails, grantConditions, grantResourcePresentation, isEffectiveAccessPayload, isGrantEffectiveAt, matchedRoleName } from '@/components/delegations/EffectiveAccess'
import type { Grant } from '@/components/delegations/GrantView'
import type { RolePreset } from '@/lib/delegations/rolePresets'

const grant = { resourceType: 'ACCOUNT', capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_READ_TRANSACTIONS'] } as Grant
const presets = [{ id: 'p1', name: 'Účetní', description: '', resourceType: 'ACCOUNT', capabilities: ['ACCOUNT_READ_TRANSACTIONS', 'ACCOUNT_READ_BALANCES'] }] as RolePreset[]

describe('effective access role matching', () => {
  it('matches a preset by resource and exact capability set regardless of ordering', () => {
    expect(matchedRoleName(grant, presets, 'cs')).toBe('Účetní')
  })

  it('does not overstate a grant that only partly resembles a preset', () => {
    expect(matchedRoleName({ ...grant, capabilities: ['ACCOUNT_READ_BALANCES'] }, presets, 'cs')).toBe('Vlastní kombinace práv')
  })

  it('does not present a historical unsupported grant as an owner role', () => {
    expect(matchedRoleName({ ...grant, capabilities: ['DELEGATION_MANAGE'] }, [{
      id: 'legacy-owner',
      name: 'Majitel účtu',
      description: '',
      resourceType: 'ACCOUNT',
      capabilities: ['DELEGATION_MANAGE'],
    }], 'en')).toBe('Historical delegated rights')
  })

  it('does not present a full card delegation as card ownership', () => {
    const capabilities = ['CARD_VIEW', 'CARD_VIEW_TRANSACTIONS', 'CARD_MANAGE_LIMITS', 'CARD_MANAGE_STATUS', 'CARD_MANAGE_CHANNELS']
    expect(matchedRoleName({ ...grant, resourceType: 'CARD', capabilities }, [{
      id: 'legacy-card-owner',
      name: 'Majitel karty',
      description: '',
      resourceType: 'CARD',
      capabilities,
    }], 'en')).toBe('Plný disponent karty')
  })

  it('requires a server evaluation timestamp instead of trusting the browser clock', () => {
    expect(isEffectiveAccessPayload({ ...grant, id: 'g1' })).toBe(false)
    expect(isEffectiveAccessPayload({ evaluatedAt: '2026-09-01T12:00:00Z', nextChangeAt: null, refreshAfterMs: null, accounts: [], cards: [], grants: [], presets: [], resourceDetails: [], sources: { accounts: 'ok' } })).toBe(true)
    expect(isEffectiveAccessPayload({ evaluatedAt: 'not-a-time', nextChangeAt: null, refreshAfterMs: null, accounts: [], cards: [], grants: [], presets: [], resourceDetails: [], sources: {} })).toBe(false)
  })

  it('explains the concrete account behind a delegation instead of showing only its UUID', () => {
    const details = [{ key: 'ACCOUNT:account-1', resourceType: 'ACCOUNT', resourceId: 'account-1', state: 'ok', detail: { accountNumber: 'CZ1234567890', currencyCode: 'CZK', status: 'ACTIVE' } }] as never
    expect(grantResourcePresentation({ ...grant, resourceId: 'account-1' }, details, 'cs')).toEqual({
      label: 'Účet •••• 7890',
      meta: 'CZK · ACTIVE',
    })
  })

  it('uses owned resources to explain grants made by the selected customer', () => {
    const details = effectiveResourceDetails({
      evaluatedAt: '2026-09-01T12:00:00Z',
      nextChangeAt: null,
      refreshAfterMs: null,
      accounts: [{ id: 'account-1', accountNumber: 'CZ1234567890', nickname: 'Provozní účet', currencyCode: 'CZK' }],
      cards: [{ id: 'card-1', maskedPan: '•••• 4321', network: 'VISA' }],
      grants: [],
      presets: [],
      resourceDetails: [],
      sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
    })

    expect(grantResourcePresentation({ ...grant, resourceId: 'account-1' }, details, 'cs')).toEqual({
      label: 'Provozní účet',
      meta: 'CZK',
    })
    expect(grantResourcePresentation({ ...grant, resourceType: 'CARD', resourceId: 'card-1' }, details, 'en')).toEqual({
      label: '•••• 4321',
      meta: 'VISA',
    })
  })

  it('prefers an explicitly resolved detail over an ownership-list fallback', () => {
    const details = effectiveResourceDetails({
      evaluatedAt: '2026-09-01T12:00:00Z',
      nextChangeAt: null,
      refreshAfterMs: null,
      accounts: [{ id: 'account-1', nickname: 'Old label' }],
      cards: [],
      grants: [],
      presets: [],
      resourceDetails: [{ key: 'ACCOUNT:account-1', resourceType: 'ACCOUNT', resourceId: 'account-1', state: 'ok', detail: { id: 'account-1', nickname: 'Current label' } }],
      sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
    })

    expect(grantResourcePresentation({ ...grant, resourceId: 'account-1' }, details, 'en').label).toBe('Current label')
  })

  it('does not use a delegated card as an owned-resource fallback', () => {
    const details = effectiveResourceDetails({
      evaluatedAt: '2026-09-01T12:00:00Z',
      nextChangeAt: null,
      refreshAfterMs: null,
      accounts: [],
      cards: [{ id: 'card-delegated', maskedPan: '•••• 2222', delegated: true }],
      grants: [],
      presets: [],
      resourceDetails: [],
      sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
    })

    expect(details).toEqual([])
  })

  it('explains financial and approval guardrails for an active operation role', () => {
    const conditions = grantConditions({
      ...grant,
      capabilities: ['ACCOUNT_INITIATE_PAYMENT'],
      approvalPolicy: 'SOLO',
      perTransactionLimit: { amount: 5000, currency: 'CZK' },
      dailyLimit: null,
      monthlyLimit: { amount: 20000, currency: 'CZK' },
      validTo: '2026-12-31T12:00:00Z',
    }, 'cs')
    expect(conditions).toEqual([
      { label: 'Platnost', value: 'do 31. 12. 2026' },
      { label: 'Schválení', value: 'samostatně' },
      { label: 'Jedna operace', value: '5 000 CZK' },
      { label: 'Denně', value: 'bez limitu' },
      { label: 'Měsíčně', value: '20 000 CZK' },
    ])
  })

  it('does not imply financial limits for a read-only role', () => {
    expect(grantConditions({ ...grant, validTo: null }, 'en')).toEqual([{ label: 'Validity', value: 'no end date' }])
  })

  it('shows the exact uncapped windows and missing end date for active action rights', () => {
    const reasons = delegationAttentionReasons({
      ...grant,
      status: 'ACTIVE',
      validTo: null,
      capabilities: ['ACCOUNT_INITIATE_PAYMENT'],
      perTransactionLimit: { amount: 5000, currency: 'CZK' },
      dailyLimit: null,
      monthlyLimit: null,
    }, new Date('2026-09-01T12:00:00Z'), 'en')

    expect(reasons).toEqual([
      {
        kind: 'no-end-date',
        label: 'Action rights without an end date',
        detail: 'Access remains active until someone changes or revokes it.',
      },
      {
        kind: 'uncapped',
        label: 'Action rights without a financial ceiling',
        detail: 'Uncapped: daily, monthly.',
      },
    ])
  })

  it('flags an active grant that expires within the review window', () => {
    expect(delegationAttentionReasons({
      ...grant,
      status: 'ACTIVE',
      validTo: '2026-09-20T12:00:00Z',
    }, new Date('2026-09-01T12:00:00Z'), 'cs')).toEqual([{
      kind: 'expiring',
      label: 'Končí do 19 dnů',
      detail: 'Ověřte, zda má přístup pokračovat.',
    }])
  })

  it('flags an inconsistent active grant whose validity already ended', () => {
    expect(delegationAttentionReasons({
      ...grant,
      status: 'ACTIVE',
      validTo: '2026-08-31T12:00:00Z',
    }, new Date('2026-09-01T12:00:00Z'), 'en')[0]).toEqual({
      kind: 'expired',
      label: 'Validity has ended',
      detail: 'The delegation is still marked active. Verify its status.',
    })
  })

  it('does not flag read-only access without an end date or inactive grants', () => {
    expect(delegationAttentionReasons({ ...grant, status: 'ACTIVE', validTo: null }, new Date('2026-09-01T12:00:00Z'), 'en')).toEqual([])
    expect(delegationAttentionReasons({ ...grant, status: 'REVOKED', validTo: '2026-09-02T12:00:00Z' }, new Date('2026-09-01T12:00:00Z'), 'en')).toEqual([])
  })

  it('uses the authority half-open validity interval for effective-now access', () => {
    const timedGrant = {
      ...grant,
      status: 'ACTIVE',
      validFrom: '2026-09-01T10:00:00Z',
      validTo: '2026-09-01T11:00:00Z',
    }

    expect(isGrantEffectiveAt(timedGrant, new Date('2026-09-01T09:59:59.999Z'))).toBe(false)
    expect(isGrantEffectiveAt(timedGrant, new Date('2026-09-01T10:00:00Z'))).toBe(true)
    expect(isGrantEffectiveAt(timedGrant, new Date('2026-09-01T10:59:59.999Z'))).toBe(true)
    expect(isGrantEffectiveAt(timedGrant, new Date('2026-09-01T11:00:00Z'))).toBe(false)
  })

  it('fails closed for malformed validity and non-active status', () => {
    const now = new Date('2026-09-01T10:00:00Z')
    expect(isGrantEffectiveAt({ ...grant, status: 'ACTIVE', validFrom: null }, now)).toBe(false)
    expect(isGrantEffectiveAt({ ...grant, status: 'ACTIVE', validFrom: 'invalid' }, now)).toBe(false)
    expect(isGrantEffectiveAt({ ...grant, status: 'ACTIVE', validFrom: '2026-01-01T00:00:00Z', validTo: '' }, now)).toBe(false)
    expect(isGrantEffectiveAt({ ...grant, status: 'REVOKED', validFrom: '2026-01-01T00:00:00Z' }, now)).toBe(false)
  })
})
