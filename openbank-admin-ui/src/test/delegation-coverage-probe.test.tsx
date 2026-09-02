// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { SessionProvider } from 'next-auth/react'
import { afterEach, describe, expect, it } from 'vitest'
import { CoverageProbe } from '@/components/delegations/CoverageProbe'
import { GrantTable } from '@/components/delegations/GrantTable'
import type { EffectiveAccessPayload } from '@/components/delegations/EffectiveAccess'
import type { Grant } from '@/components/delegations/GrantView'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

describe('delegation coverage probe authority semantics', () => {
  it('remounts with the newly loaded grant instead of retaining stale capability state', () => {
    const first = grant('first', ['ACCOUNT_READ_BALANCES'])
    const second = grant('second', ['CARD_VIEW'], 'CARD')
    const { rerender } = renderProbe(first)

    fireEvent.change(screen.getByRole('textbox', { name: 'Amount to probe' }), { target: { value: '125' } })
    expect(screen.getByRole('combobox', { name: 'Capability to probe' })).toHaveValue('ACCOUNT_READ_BALANCES')

    rerender(<LanguageProvider><CoverageProbe key={second.id} grant={second} /></LanguageProvider>)

    expect(screen.getByRole('combobox', { name: 'Capability to probe' })).toHaveValue('CARD_VIEW')
    expect(screen.queryByRole('option', { name: 'ACCOUNT_READ_BALANCES' })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Amount to probe' })).toHaveValue('')
  })

  it('does not offer a historical unsupported capability to the generic authority check', () => {
    renderProbe(grant('legacy', ['DELEGATION_MANAGE']))

    expect(screen.getByRole('note')).toHaveTextContent('no effective authority that can be probed')
    expect(screen.queryByRole('combobox', { name: 'Capability to probe' })).not.toBeInTheDocument()
    expect(screen.queryByText('DELEGATION_MANAGE')).not.toBeInTheDocument()
  })
})

describe('delegation list authority semantics', () => {
  it('separates a historical unsupported value from effective rights', () => {
    const historical = grant('legacy-list', ['ACCOUNT_READ_BALANCES', 'DELEGATION_MANAGE'])
    const effectiveAccess: EffectiveAccessPayload = {
      evaluatedAt: '2026-09-02T12:00:00Z',
      nextChangeAt: null,
      refreshAfterMs: null,
      accounts: [],
      cards: [],
      grants: [historical],
      presets: [],
      resourceDetails: [],
      sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
    }

    render(
      <SessionProvider session={{ user: { roles: ['ROLE_ADMIN'] } } as never}>
        <LanguageProvider>
          <GrantTable title="Received" subtitle="" grants={[historical]} state="ok" direction="received" effectiveAccess={effectiveAccess} />
        </LanguageProvider>
      </SessionProvider>,
    )

    expect(screen.getByText('Historical delegated rights')).toBeVisible()
    const evidence = screen.getByRole('note', { name: 'Legacy unsupported capabilities' })
    const rightsCell = within(evidence.closest('td')!)
    expect(rightsCell.getByLabelText('Effective rights')).toContainElement(rightsCell.getByTitle('ACCOUNT_READ_BALANCES'))
    expect(within(rightsCell.getByLabelText('Effective rights')).queryByTitle('DELEGATION_MANAGE')).not.toBeInTheDocument()
    expect(within(evidence).getByTitle('DELEGATION_MANAGE')).toHaveTextContent('not enforced')
  })
})

function renderProbe(value: Grant) {
  return render(<LanguageProvider><CoverageProbe key={value.id} grant={value} /></LanguageProvider>)
}

function grant(id: string, capabilities: string[], resourceType = 'ACCOUNT'): Grant {
  return {
    id,
    grantorPartyId: 'grantor',
    granteePartyId: 'grantee',
    resourceType,
    resourceId: 'resource',
    capabilities,
    status: 'ACTIVE',
  }
}
