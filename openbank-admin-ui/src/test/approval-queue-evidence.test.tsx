// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ApprovalsPage from '@/app/approvals/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parseAgentProposalList, parseApprovalInbox } from '@/lib/approvals/evidence'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { email: 'checker@example.test', roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const PROPOSAL_ID = '00000000-0000-4000-8000-000000000101'
const proposal = {
  id: PROPOSAL_ID,
  title: 'Rotate a key',
  rationale: 'The current key reaches its policy age.',
  suggestedAction: 'Open a governed rotation ticket',
  proposedBy: 'security-agent',
  proposedAt: '2026-09-09T10:00:00Z',
  state: 'PROPOSED',
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  modelId: null,
}
const sourceNames = [
  'lending', 'sanctions', 'transaction', 'domestic-payment', 'clearing', 'fx', 'ledger', 'swift',
  'sepa-payment', 'sepa-instant', 'notification', 'party', 'account', 'consent', 'balance', 'billing',
  'delegation', 'agent',
]
const inbox = { items: [], sources: Object.fromEntries(sourceNames.map(name => [name, 'ok'])) }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function mount() {
  return render(<LanguageProvider><ApprovalsPage /></LanguageProvider>)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('approval queue evidence contracts', () => {
  it('accepts verified empty queues and rejects guessed or duplicate evidence', () => {
    expect(parseAgentProposalList([])).toEqual([])
    expect(parseAgentProposalList({ items: [] })).toBeNull()
    expect(parseAgentProposalList([proposal, proposal])).toBeNull()
    expect(parseAgentProposalList([{ ...proposal, proposedAt: 'not-an-instant' }])).toBeNull()
    expect(parseApprovalInbox(inbox)).toEqual(inbox)
    expect(parseApprovalInbox({ items: [], sources: {} })).toBeNull()
    expect(parseApprovalInbox({ ...inbox, items: [{ id: 'x', domain: 'unknown', action: 'act', resourceId: null, maker: null, proposedAt: null }] })).toBeNull()
  })

  it('does not turn malformed agent evidence into a clear queue', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json({ items: [] })
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText(/Failed to load: AI proposal queue|Načtení selhalo: Fronta AI návrhů/)).toBeInTheDocument()
    expect(screen.queryByText(/No proposals awaiting approval|Žádné návrhy nečekají/)).not.toBeInTheDocument()
  })

  it('preserves and labels the last verified proposal after a failed refresh', async () => {
    let proposalReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) {
        proposalReads += 1
        return proposalReads === 1 ? json([proposal]) : json({ error: 'agent_unreachable' }, 502)
      }
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText('Rotate a key')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Refresh approval queue|Obnovit schvalovací frontu/ }))

    expect(await screen.findByText(/Showing the last verified proposals|Zobrazuji poslední ověřené návrhy/)).toBeInTheDocument()
    expect(screen.getByText('Rotate a key')).toBeInTheDocument()
    await waitFor(() => expect(proposalReads).toBe(2))
  })

  it('renders a genuine empty claim only after both reads are verified', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([])
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText(/No proposals awaiting approval|Žádné návrhy nečekají/)).toBeInTheDocument()
    expect(screen.getByText(/No domain approvals pending|Žádná doménová schvalování nečekají/)).toBeInTheDocument()
  })

  it('recovers from an unavailable agent queue through the explicit retry', async () => {
    let proposalReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) {
        proposalReads += 1
        return proposalReads === 1 ? json({ error: 'agent_unreachable' }, 502) : json([])
      }
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    fireEvent.click(await screen.findByRole('button', { name: /Retry|Zkusit znovu/ }))
    expect(await screen.findByText(/No proposals awaiting approval|Žádné návrhy nečekají/)).toBeInTheDocument()
    expect(screen.queryByText(/Agent-service is not responding|Agent-service neodpovídá/)).not.toBeInTheDocument()
  })

  it('preserves the last verified domain approval when the next inbox is malformed', async () => {
    let inboxReads = 0
    const approval = { id: 'approval-42', domain: 'sanctions', action: 'sanctions.clear', resourceId: 'screening-7', maker: 'maker@example.test', proposedAt: '2026-09-09T09:00:00Z' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([])
      if (url.includes('/api/approvals/pending')) {
        inboxReads += 1
        return inboxReads === 1 ? json({ ...inbox, items: [approval] }) : json({ items: [], sources: {} })
      }
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText('sanctions.clear')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Refresh approval queue|Obnovit schvalovací frontu/ }))
    expect(await screen.findByText(/Domain approval inbox could not be loaded|Doménovou schvalovací frontu se nepodařilo načíst/)).toBeInTheDocument()
    expect(screen.getByText('sanctions.clear')).toBeInTheDocument()
  })

  it('treats a malformed federated inbox as unavailable, not empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([])
      if (url.includes('/api/approvals/pending')) return json({ items: [], sources: {} })
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText(/Domain approval inbox could not be loaded|Doménovou schvalovací frontu se nepodařilo načíst/)).toBeInTheDocument()
    expect(screen.queryByText(/No domain approvals pending|Žádná doménová schvalování nečekají/)).not.toBeInTheDocument()
  })
})
