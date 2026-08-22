// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IaopsCaseThreadPage from '@/app/iaops/cases/[caseId]/page'

vi.mock('next/navigation', () => ({ useParams: () => ({ caseId: 'case-17' }) }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))
vi.mock('@/components/feedback/DataUnavailable', () => ({ DataUnavailable: () => null }))

const THREAD = {
  caseId: 'case-17',
  caseClass: 'ALERT_REVIEW',
  dispositionTarget: 'operator',
  status: 'SYNTHESIZED',
  openedAtEpochMs: 1_700_000_000_000,
  deadlineAtEpochMs: 1_700_000_600_000,
  contestedRate: 0,
  budgetTokens: 200000,
  budgetContributions: 40,
  observedAtEpochMs: 1_700_000_200_000,
  dataFromEpochMs: 1_700_000_000_000,
  dataToEpochMs: 1_700_000_200_000,
  lastSuccessfulLoadEpochMs: 1_700_000_300_000,
  coverageStatus: 'UNKNOWN_RETENTION',
  historySource: 'case-coordinator-postgres-read-model',
  retentionPolicy: 'not-configured',
  entries: [
    { type: 'CASE_OPENED', atEpochMs: 1_700_000_000_000, actor: 'case-coordinator', runtimeEvidence: { evidenceId: 'case-17', source: 'case-coordinator-postgres-read-model', stage: 'RECORDED', observedAtEpochMs: 1_700_000_000_000, correlationId: 'case-17', detail: 'Persisted case workflow record' } },
    { type: 'CONTRIBUTION', atEpochMs: 1_700_000_100_000, actor: 'aml-agent', evidenceRefs: ['alert-17'], runtimeEvidence: { evidenceId: 'contribution-17', source: 'case-coordinator-postgres-read-model', stage: 'PERSISTED', observedAtEpochMs: 1_700_000_100_000, correlationId: 'case-17', detail: 'Contribution persisted in the durable case read model' } },
    { type: 'PROPOSAL_EMITTED', atEpochMs: 1_700_000_200_000, proposalType: 'REVIEW', runtimeEvidence: { evidenceId: 'proposal-17', source: 'case-coordinator-transactional-outbox', stage: 'PUBLISHED_TO_BROKER', observedAtEpochMs: 1_700_000_200_000, correlationId: 'case-17', detail: 'Proposal outbox status: SENT' } },
  ],
}

afterEach(() => vi.unstubAllGlobals())

describe('AIOps case thread proposal event', () => {
  it('labels a proposal as a recorded event and keeps the HITL link neutral', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ available: true, thread: THREAD }) }))

    render(<IaopsCaseThreadPage />)

    await screen.findByRole('heading', { name: 'Proposal recorded in the thread' })
    expect(screen.getAllByText('Proposal recorded in the thread')).toHaveLength(3)
    expect(screen.getByText('The coordinator created a proposal event. This page does not track delivery or the human decision.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Browse the HITL queue' })).toHaveAttribute('href', '/approvals')
    expect(screen.queryByText('Open the HITL queue for approval')).not.toBeInTheDocument()
  })

  it('renders only observed runtime edges as solid topology with expandable provenance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ available: true, thread: THREAD }) }))

    render(<IaopsCaseThreadPage />)
    await screen.findByRole('heading', { name: 'Proposal recorded in the thread' })
    fireEvent.click(screen.getByRole('button', { name: 'topology' }))

    expect(await screen.findByRole('region', { name: 'Evidence-backed runtime topology' })).toBeInTheDocument()
    expect(screen.getByText('aml-agent')).toBeInTheDocument()
    expect(screen.getByText('proposal event broker')).toBeInTheDocument()
    expect(screen.getByText(/Charter declarations alone never create a solid edge/)).toBeInTheDocument()
    expect(screen.getAllByText(/PERSISTED|PUBLISHED_TO_BROKER/).length).toBeGreaterThan(0)
    expect(screen.getByText(/unknown retention/)).toBeInTheDocument()
  })
})
