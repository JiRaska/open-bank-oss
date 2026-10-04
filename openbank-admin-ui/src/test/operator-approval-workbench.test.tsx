// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`
const viewer = { username: 'checker' }
vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Checker', roles: ['ROLE_OPERATOR'], accessToken: jwt({ preferred_username: viewer.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
}))
// The party chip resolves names over its own fetch; keep it out of the decision fetch sequence.
vi.mock('@/components/entities/EntityChip', () => ({
  EntityChip: ({ id }: { id: string }) => <span data-testid="party-chip" data-party={id}>Party</span>,
}))

import { OperatorApprovalWorkbench } from '@/components/approvals/OperatorApprovalWorkbench'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { approvalWorkbenchHref } from '@/lib/approvals/triage'
import { approvalTarget, isOwnRequest } from '@/lib/approvals/operator'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const party = 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876'
const deviceSummary = `action=device.revoke party=${party} device=abcdef01… credential=cred-7f3… algorithm=ES256 enrolledAt=2026-09-01`
const approval = { id, action: 'device.revoke', resourceId: party, status: 'PENDING', makerId: 'maker', createdAt: '2026-09-14T00:00:00Z', decidedBy: null, summary: deviceSummary }
const settlement = {
  ...approval, action: 'settlement.create', resourceId: null, decidedAt: null, claimedAt: null,
  expiresAt: '2026-09-14T00:15:00Z', expired: false, summary: 'POST /api/v1/settlements amount=250.00 CZK',
}

function mount(domain: 'sca' | 'settlement' = 'sca') {
  return render(<LanguageProvider initialLanguage="en"><OperatorApprovalWorkbench domain={domain} id={id} /></LanguageProvider>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); viewer.username = 'checker' })

describe('operator approval workbench (SCA #11903, settlement #11915)', () => {
  it('links both queues to their governed review', () => {
    const item = { id, action: approval.action, resourceId: party, maker: 'maker', proposedAt: approval.createdAt }
    expect(approvalWorkbenchHref({ ...item, domain: 'sca' })).toBe(`/approvals/sca/${id}`)
    expect(approvalWorkbenchHref({ ...item, domain: 'settlement' })).toBe(`/approvals/settlement/${id}`)
  })

  it('confirms exactly one decision after an explicit review', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(approval))
      .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    vi.stubGlobal('fetch', fetcher)
    mount()
    const approve = await screen.findByRole('button', { name: 'Approve' })
    expect(approve).toBeDisabled()
    expect(screen.getByTestId('party-chip')).toHaveAttribute('data-party', party)
    // The approval id is a secondary, shortened reference — never the primary text.
    expect(screen.queryByText(id)).not.toBeInTheDocument()
    expect(screen.getAllByText('SCA device revocation').length).toBeGreaterThan(0)
    // The device the approval binds is shown, so the checker no longer has to ask the maker.
    expect(screen.getByText(deviceSummary)).toBeInTheDocument()
    expect(screen.queryByText(/confirm it with the maker/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(approve)
    expect(fetcher).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Back to review' }))
    const confirm = screen.getByRole('button', { name: 'Confirm decision' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1][0]).toBe(`/api/sca/approvals/${id}`)
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ approve: true })
    finish(Response.json({ ...approval, status: 'APPROVED', decidedBy: 'checker' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.getByText(/Approval recorded/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    // The SCA decision response carries no summary; the reviewed device stays on screen.
    expect(screen.getByText(deviceSummary)).toBeInTheDocument()
  })

  it('shows an enrollment by key fingerprint and a consume with its masked creditor', async () => {
    const enroll = `action=device.enroll party=${party} credential=webauthn… algorithm=ES256 keySha256=3f9a0c12`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, action: 'device.enroll', summary: enroll })))
    const view = mount()
    expect(await screen.findByText(enroll)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    view.unmount()
    const consume = 'action=scaChallenge.consume challenge=9c1e… purpose=PAYMENT_INITIATION amount=1250.50 CZK creditor=…5399'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, action: 'scaChallenge.consume', summary: consume })))
    mount()
    expect(await screen.findByText(consume)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })

  it('offers no decision on an SCA approval without a bound-request summary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, summary: null })))
    mount()
    await screen.findByText('This request type cannot be safely reviewed in this interface.')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(approvalTarget('sca', { ...approval, summary: '   ' } as never)).toBeNull()
    expect(approvalTarget('sca', approval as never)).toEqual({ kind: 'device', party, summary: deviceSummary })
  })

  it('never offers the maker a decision on their own request', async () => {
    viewer.username = 'maker'
    const fetcher = vi.fn().mockResolvedValue(Response.json(approval))
    vi.stubGlobal('fetch', fetcher)
    mount()
    await screen.findByText('You created this request. A different operator must decide it.')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('treats an unknown viewer or maker as not-own, leaving the server as the control', () => {
    expect(isOwnRequest({ makerId: 'maker' }, null)).toBe(false)
    expect(isOwnRequest({ makerId: null }, 'maker')).toBe(false)
    expect(isOwnRequest({ makerId: 'maker' }, 'maker')).toBe(true)
  })

  it('shows the bound settlement instruction and decides through the settlement BFF', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(settlement))
      .mockResolvedValueOnce(Response.json({ ...approval, action: 'settlement.create', resourceId: null, status: 'REJECTED', decidedBy: 'checker' }))
    vi.stubGlobal('fetch', fetcher)
    mount('settlement')
    await screen.findByText(settlement.summary)
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm decision' }))
    await screen.findByText('The request was rejected.')
    expect(fetcher.mock.calls[1][0]).toBe(`/api/settlements/approvals/${id}`)
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ approve: false })
    // The decision response carries no summary; the reviewed instruction stays on screen.
    expect(screen.getByText(settlement.summary)).toBeInTheDocument()
  })

  it('offers no decision on an expired settlement approval or one without a summary', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...settlement, expired: true })))
    const view = mount('settlement')
    await screen.findByText(/expired/)
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    view.unmount()
    expect(approvalTarget('settlement', { ...settlement, status: 'PENDING', summary: null } as never)).toBeNull()
  })

  it('reloads an uncertain decision instead of allowing a blind retry', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(approval))
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(Response.json({ ...approval, status: 'REJECTED', decidedBy: 'checker' }))
    vi.stubGlobal('fetch', fetcher)
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm decision' }))
    await screen.findByText(/response is missing/)
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reload state' }))
    await screen.findByText('Rejected')
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(fetcher.mock.calls[2][1].method).toBeUndefined()
  })

  it('does not allow decisions on an unknown action, a malformed resource or a different returned id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, resourceId: 'not-a-party' })))
    const view = mount()
    await screen.findByText('This request type cannot be safely reviewed in this interface.')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    view.unmount()
    expect(approvalTarget('sca', { ...approval, action: 'device.list' } as never)).toBeNull()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, id: party })))
    mount()
    await waitFor(() => expect(screen.queryByText('Loading approval…')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
