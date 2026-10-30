// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ApprovalsPage from '@/app/approvals/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parseAgentProposalList, parseApprovalInbox } from '@/lib/approvals/evidence'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

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
  'delegation', 'agent', 'communication', 'treasury', 'ledger-backfill',
  'compliance-pack', 'campaign', 'audience', 'identity-case', 'sca', 'settlement',
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
  it('simulates operator session through the real BFF mapper into the rendered billing queue', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'mock-operator-token', roles: ['ROLE_ADMIN'] } } as never)
    const providerCalls: Array<{ url: string; authorization: string | undefined }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/approvals/pending') return (await import('@/app/api/approvals/pending/route')).GET()
      if (url.startsWith('/api/agent/proposals')) return json([])
      if (url.startsWith('/api/governance/agent-identities')) return json({ available: true, agents: [] })
      providerCalls.push({ url, authorization: new Headers(init?.headers).get('authorization') ?? undefined })
      if (url.includes('/api/v1/fees/approvals')) return json([{
        id: 'fee-approval-7', action: 'billing.feeWaiver', resourceId: 'fee-7',
        makerId: 'agent:reviewer', makerActorKind: 'AI_AGENT', createdAt: '2026-09-24T10:00:00Z',
      }])
      return json({ error: 'mock provider unavailable' }, 503)
    }))

    mount()

    const row = await screen.findByTestId('domain-approval-billing:fee-approval-7')
    expect(row).toHaveTextContent('billing.feeWaiver')
    expect(row.querySelector('[data-testid="approval-maker"]')).toHaveTextContent('agent:reviewer')
    expect(row.querySelector('[data-testid="approval-maker-kind"]')).toHaveTextContent('AI agent')
    expect(row.querySelector('[data-testid="approval-resource"]')).toHaveTextContent('fee-7')
    expect(row.querySelector('time[datetime="2026-09-24T10:00:00Z"]')).not.toBeNull()
    expect(providerCalls.find(call => call.url.includes('/api/v1/fees/approvals'))?.authorization).toBe('Bearer mock-operator-token')
    expect(screen.queryByText(/No domain approvals pending|Žádná doménová schvalování nečekají/)).not.toBeInTheDocument()
  })

  it('accepts verified empty queues and rejects guessed or duplicate evidence', () => {
    expect(parseAgentProposalList([])).toEqual([])
    expect(parseAgentProposalList({ items: [] })).toBeNull()
    expect(parseAgentProposalList([proposal, proposal])).toBeNull()
    expect(parseAgentProposalList([{ ...proposal, proposedAt: 'not-an-instant' }])).toBeNull()
    expect(parseApprovalInbox(inbox)).toEqual(inbox)
    expect(parseApprovalInbox({ items: [], sources: {} })).toBeNull()
    expect(parseApprovalInbox({ ...inbox, items: [{ id: 'x', domain: 'unknown', action: 'act', resourceId: null, maker: null, proposedAt: null }] })).toBeNull()
    expect(parseApprovalInbox({ ...inbox, items: [{ id: 'x', domain: 'billing', action: 'act', resourceId: null, maker: 'agent:someone', makerActorKind: 'HUMAN_BY_NAME', proposedAt: null }] })).toBeNull()
  })

  it('accepts the communication source and item returned by the federated BFF', () => {
    const item = {
      id: 'communication-approval-7', domain: 'communication', action: 'communication.publish',
      resourceId: 'message-7', maker: 'operator@example.test', proposedAt: '2026-09-24T10:00:00Z',
    }
    expect(parseApprovalInbox({ ...inbox, items: [item] })).toEqual({ ...inbox, items: [{ ...item, makerActorKind: 'UNKNOWN' }] })
  })

  it('rejects a non-string maker kind rather than presenting it as verified', () => {
    const item = {
      id: 'approval-7', domain: 'billing', action: 'billing.feeWaiver',
      resourceId: 'fee-7', maker: 'maker-7', proposedAt: '2026-09-24T10:00:00Z',
    }
    expect(parseApprovalInbox({ ...inbox, items: [{ ...item, makerActorKind: ['HUMAN'] }] })).toBeNull()
    expect(parseApprovalInbox({ ...inbox, items: [{ ...item, makerActorKind: null }] })).toBeNull()
  })

  it('does not infer an AI maker from an agent-shaped display id', () => {
    const item = {
      id: 'legacy-approval-7', domain: 'billing', action: 'billing.feeWaiver',
      resourceId: 'fee-7', maker: 'agent:unverified', proposedAt: '2026-09-24T10:00:00Z',
    }
    expect(parseApprovalInbox({ ...inbox, items: [item] }))
      .toEqual({ ...inbox, items: [{ ...item, makerActorKind: 'UNKNOWN' }] })
  })

  it('shows treasury and backfill makers with governed hand-offs', async () => {
    const items = [
      { id: 'deal-7', domain: 'treasury', action: 'treasury.MM_PLACEMENT', resourceId: 'deal-7', maker: 'dealer.two', proposedAt: '2026-09-20T10:00:00Z' },
      { id: 'request-7', domain: 'ledger-backfill', action: 'lending.ledgerBackfill.decide', resourceId: 'request-7', maker: 'finance.one', proposedAt: '2026-09-20T11:00:00Z' },
    ]
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([])
      if (url.includes('/api/approvals/pending')) return json({ ...inbox, items })
      return json({ available: true, agents: [] })
    }))
    mount()

    const treasury = await screen.findByTestId('domain-approval-treasury:deal-7')
    expect(treasury.querySelector('[data-testid="approval-maker"]')).toHaveTextContent('dealer.two')
    expect(treasury.querySelector('a[href="/treasury/deals/deal-7"]')).not.toBeNull()
    const backfill = screen.getByTestId('domain-approval-ledger-backfill:request-7')
    expect(backfill.querySelector('[data-testid="approval-maker"]')).toHaveTextContent('finance.one')
    expect(backfill.querySelector('a[href="/balance-sheet/ledger-backfill"]')).not.toBeNull()
  })

  it('does not guess a communication maker kind when its provider omits provenance', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([])
      if (url.includes('/api/approvals/pending')) return json({ ...inbox, items: [{
        id: 'communication-approval-7', domain: 'communication', action: 'communication.publish',
        resourceId: 'message-7', maker: 'operator@example.test', proposedAt: '2026-09-24T10:00:00Z',
      }] })
      return json({ available: true, agents: [] })
    }))
    mount()

    const row = await screen.findByTestId('domain-approval-communication:communication-approval-7')
    expect(row).toHaveTextContent('communication.publish')
    expect(row.querySelector('[data-testid="approval-resource"]')).toHaveTextContent('message-7')
    expect(row.querySelector('[data-testid="approval-maker"]')).toHaveTextContent('operator@example.test')
    expect(row.querySelector('[data-testid="approval-maker-kind"]')).toHaveTextContent('Origin unverified')
    expect(row.querySelector('time[datetime="2026-09-24T10:00:00Z"]')).not.toBeNull()
    expect(row.querySelector('a[href="/approvals/communication"]')).not.toBeNull()
  })

  it('does not guess AI authorship from an unverified proposer name', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([{ ...proposal, proposedBy: 'risk-agent-review-desk' }])
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText('Rotate a key')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-proposer-kind="unverified"]')).not.toBeNull())
    expect(document.querySelector('[data-proposer-kind="agent"]')).toBeNull()
    expect(screen.getByText(/authorship cannot be verified|Původ tohoto návrhu nelze ověřit/)).toBeInTheDocument()
    expect(screen.queryByText(/This proposal was generated by AI|Tento návrh vytvořila AI/)).not.toBeInTheDocument()
  })

  it('does not treat a legacy user icon as proof of a human proposer', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/agent/proposals')) return json([{ ...proposal, proposedBy: 'review-desk', agent: { id: 'review-desk', displayName: 'Review Desk', icon: 'user', charterKnown: false } }])
      if (url.includes('/api/approvals/pending')) return json(inbox)
      return json({ available: true, agents: [] })
    }))
    mount()

    expect(await screen.findByText('Rotate a key')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-proposer-kind="unverified"]')).not.toBeNull())
    expect(screen.getByText(/authorship cannot be verified|Původ tohoto návrhu nelze ověřit/)).toBeInTheDocument()
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
