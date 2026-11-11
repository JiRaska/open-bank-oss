// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PolicyRevisions } from '@/components/lending/risk/PolicyRevisions'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ id: 'checker', roles: ['ROLE_CREDIT_RISK'] }))
vi.mock('next-auth/react', () => ({ useSession: () => ({ data: { user: session }, status: 'authenticated' }) }))

const proposal = {
  id: '00000000-0000-7000-8000-000000000003',
  revision: 3,
  effectiveFrom: '2027-01-01',
  contentHash: 'b'.repeat(64),
  state: 'PROPOSED',
  proposedBy: 'maker',
  policyJson: '{"threshold":0.123456789123456789}',
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const view = () => render(<LanguageProvider><PolicyRevisions /></LanguageProvider>)

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
beforeEach(() => { session.id = 'checker'; session.roles = ['ROLE_CREDIT_RISK'] })

describe('Czech credit policy revisions', () => {
  it('saves the proposed bundle through the service relay and withholds own decision controls', async () => {
    session.id = 'maker'
    const fetcher = vi.fn(async (_path: string, init: RequestInit) =>
      init.method === 'POST' ? json(proposal, 201) : json([proposal]))
    vi.stubGlobal('fetch', fetcher)
    view()
    fireEvent.change(screen.getByLabelText(/policy JSON for a new version/i), { target: { value: proposal.policyJson } })
    fireEvent.click(screen.getByRole('button', { name: /save proposal/i }))
    await screen.findByRole('status')
    expect(fetcher).toHaveBeenCalledWith(expect.stringMatching(/\/api\/svc\/lending-service\/.+\/proposals$/),
      expect.objectContaining({ method: 'POST', body: proposal.policyJson }))
    fireEvent.click(await screen.findByRole('button', { name: /version 3 from 2027-01-01/i }))
    expect(screen.getByText(proposal.contentHash)).toBeInTheDocument()
    expect(screen.getByText(proposal.policyJson)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /approve revision/i })).not.toBeInTheDocument()
  })

  it('reviews the immutable payload and posts an explicit decision for its exact identifier', async () => {
    let executed = false
    const fetcher = vi.fn(async (_path: string, init: RequestInit) => {
      if (init.method === 'POST') {
        executed = true
        return json({ ...proposal, state: 'EXECUTED' })
      }
      return json(executed ? [] : [proposal])
    })
    vi.stubGlobal('fetch', fetcher)
    view()
    fireEvent.click(await screen.findByRole('button', { name: /version 3 from 2027-01-01/i }))
    expect(screen.getByText(proposal.contentHash)).toBeInTheDocument()
    expect(screen.getByText(proposal.policyJson)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/decision reason/i), { target: { value: 'Reviewed the synthetic fixture' } })
    fireEvent.click(screen.getByRole('button', { name: /approve revision/i }))
    expect(await screen.findByRole('status')).toHaveTextContent('Approved effective 2027-01-01.')
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining(`/proposals/${proposal.id}/decide`),
      expect.objectContaining({ method: 'POST', body: '{"approve":true,"reason":"Reviewed the synthetic fixture"}' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /version 3 from/i })).not.toBeInTheDocument())
  })

  it('does not load the queue for a compliance reader without the credit risk role', () => {
    session.roles = ['ROLE_COMPLIANCE']
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    view()
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
    expect(fetcher).not.toHaveBeenCalled()
  })
})
