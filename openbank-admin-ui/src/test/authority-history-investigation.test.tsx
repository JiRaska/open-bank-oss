// SPDX-License-Identifier: Apache-2.0
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthorityHistoryInvestigation } from '@/components/context/AuthorityHistoryInvestigation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
const id = '11111111-1111-4111-8111-111111111111', at = '2026-09-17T12:00:00Z'
function fixture() {
  return { root: `delegation:${id}`, effectiveAt: at, knownAt: at, truncated: false, actionAuthorization: 'UNKNOWN', observations: [2, 1].map(revision => ({
    evidence: { delegationId: id, revision, eventType: revision === 2 ? 'DelegationRevoked' : 'DelegationActivated', grantorPartyId: '22222222-2222-4222-8222-222222222222', granteePartyId: '33333333-3333-4333-8333-333333333333', resourceType: 'ACCOUNT', resourceId: '44444444-4444-4444-8444-444444444444', capabilities: ['ACCOUNT_READ'], approvalPolicy: 'SOLO', requiredApprovals: null, validFrom: '2026-09-16T00:00:00Z', validTo: null, occurredAt: `2026-09-17T0${revision}:00:00Z` }, recordedAt: at, evidenceRef: `delegation:${id}:${revision}`, contentHash: String(revision).repeat(64),
  })) }
}
function mount() { render(<LanguageProvider initialLanguage="en"><AuthorityHistoryInvestigation /></LanguageProvider>) }
function submit() {
  fireEvent.change(screen.getByLabelText('Delegation ID'), { target: { value: id } })
  fireEvent.change(screen.getByLabelText('Assigned case ID'), { target: { value: 'case-synthetic' } })
  fireEvent.click(screen.getByRole('button', { name: 'Load history' }))
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('authority history investigation', () => {
  it('renders source relations and switches the selected observation with provenance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture())))); mount(); submit()
    expect(await screen.findByRole('img', { name: 'Delegation observation graph' })).toBeInTheDocument()
    expect(screen.getByText('Grantor')).toBeInTheDocument(); expect(screen.getByText('Grantee')).toBeInTheDocument()
    expect(screen.getByText(/Authorization of a specific business action: UNKNOWN/)).toBeInTheDocument()
    const revoked = screen.getByRole('button', { name: /DelegationRevoked/ }), activated = screen.getByRole('button', { name: /DelegationActivated/ })
    expect(revoked).toHaveAttribute('aria-pressed', 'true'); fireEvent.click(activated)
    expect(activated).toHaveAttribute('aria-pressed', 'true'); expect(revoked).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText(/SHA-256: 111111/)).toBeInTheDocument()
  })
  it('keeps an empty source history unknown without fabricating a graph', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...fixture(), observations: [] })))); mount(); submit()
    expect(await screen.findByText(/No history is available in this time scope/)).toBeInTheDocument(); expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
  it('clears observations immediately after an assigned-case edit', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture())))); mount(); submit(); await screen.findByRole('img')
    fireEvent.change(screen.getByLabelText('Assigned case ID'), { target: { value: 'case-other' } })
    expect(screen.queryByRole('img')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /DelegationRevoked/ })).not.toBeInTheDocument()
  })
  it('drops a late response after the time scope changes', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(done => { resolve = done }))); mount(); submit()
    fireEvent.change(screen.getByLabelText('Known through (ISO, optional)'), { target: { value: '2026-09-16T00:00:00Z' } })
    await act(async () => { resolve(new Response(JSON.stringify(fixture()))) })
    expect(screen.queryByRole('img')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /DelegationRevoked/ })).not.toBeInTheDocument()
  })
})
