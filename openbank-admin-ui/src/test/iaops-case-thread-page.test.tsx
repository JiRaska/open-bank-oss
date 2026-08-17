// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { render, screen } from '@testing-library/react'
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
  entries: [
    { type: 'CASE_OPENED', atEpochMs: 1_700_000_000_000, actor: 'case-coordinator' },
    { type: 'CONTRIBUTION', atEpochMs: 1_700_000_100_000, actor: 'aml-agent', evidenceRefs: ['alert-17'] },
    { type: 'PROPOSAL_EMITTED', atEpochMs: 1_700_000_200_000, proposalType: 'REVIEW' },
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
})
