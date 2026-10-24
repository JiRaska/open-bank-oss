// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MergeRecordsSection } from '@/components/agent/MergeRecordsSection'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

function record(overrides: Record<string, unknown>) {
  return {
    id: '3f6f8d9a-2b7c-4e1a-9c5d-8a1f2b3c4d5e',
    agentId: 'finops-agent',
    prNumber: 123,
    prUrl: 'https://github.com/openbank/open-bank-oss/pull/123',
    headBranch: 'agent/finops-agent-budget',
    mergedBy: 'jiri.raska',
    mergedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
    title: 'Tighten idle namespace budget',
    proposalId: null,
    recordedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000 + 5000).toISOString(),
    source: 'github-actions',
    ...overrides,
  }
}

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, json: async () => body }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('MergeRecordsSection', () => {
  it('renders recorded merges with a linked PR and a distinct human badge on mergedBy', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([
      record({}),
      record({
        id: '4a7b9c0d-3e8f-4a2b-8d1e-9b2c3d4e5f60',
        agentId: 'not-yet-chartered',
        prNumber: 99,
        prUrl: 'https://github.com/openbank/open-bank-oss/pull/99',
        headBranch: 'agent/not-yet-chartered-docs',
        mergedBy: 'alice',
        mergedAt: new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString(),
        title: 'Old merge from an unchartered agent id',
      }),
    ])))

    render(<MergeRecordsSection knownAgentIds={['finops-agent']} />)

    expect(await screen.findByText('Recorded human merges')).toBeVisible()
    // Summary chip renders only after the fetch resolves (records !== null), and
    // the title above renders immediately — so the chip must be AWAITED, not
    // grabbed synchronously, or the assertion races the fetch (CI flake).
    expect(await screen.findByText('2 recorded · 1 in the last 30 days')).toBeVisible()

    // PR number links to prUrl.
    const prLink = screen.getByRole('link', { name: '#123' })
    expect(prLink).toHaveAttribute('href', 'https://github.com/openbank/open-bank-oss/pull/123')

    // Agent display name from the charter personas; raw agentId as the fallback.
    expect(screen.getByText('Fina')).toBeVisible()
    expect(screen.getByText('not-yet-chartered')).toBeVisible()

    // mergedBy is rendered as the HUMAN audit actor, distinctly marked.
    expect(screen.getByText('jiri.raska')).toBeVisible()
    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getAllByTitle('Human — the audited actor of this merge').length).toBe(2)

    expect(screen.getByText('agent/finops-agent-budget')).toBeVisible()
    expect(screen.getByText('Tighten idle namespace budget')).toBeVisible()
  })

  it('renders the empty state without claiming phase 3 is reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([])))

    render(<MergeRecordsSection knownAgentIds={[]} />)

    expect(await screen.findByText(
      'No recorded merges yet — merge recording activates when the first agent PR is merged (ADR-0031 phase 3).',
    )).toBeVisible()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows a quiet inline warning — not a red banner — when the fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 502, json: async () => ({ error: 'agent_unreachable' }) })))

    render(<MergeRecordsSection knownAgentIds={[]} />)

    expect(await screen.findByText(/Merge records are unavailable right now/)).toBeVisible()
    expect(screen.getByText(/it is not a governance incident/)).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('drops malformed upstream rows and falls through to the empty state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([record({ mergedAt: 'not-a-date' })])))

    render(<MergeRecordsSection knownAgentIds={[]} />)

    expect(await screen.findByText(
      'No recorded merges yet — merge recording activates when the first agent PR is merged (ADR-0031 phase 3).',
    )).toBeVisible()
  })

  it('survives a network throw with the quiet warning', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))

    render(<MergeRecordsSection knownAgentIds={[]} />)

    expect(await screen.findByText(/Merge records are unavailable right now/)).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
