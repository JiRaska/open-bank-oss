// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IaopsCasesPage from '@/app/iaops/cases/page'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))
vi.mock('@/components/feedback/DataUnavailable', () => ({ DataUnavailable: () => null }))

afterEach(() => vi.unstubAllGlobals())

describe('AIOps case list', () => {
  it('exposes the selected status filter without relying on colour', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ available: true, cases: [] }) }))

    render(<IaopsCasesPage />)

    expect(await screen.findByRole('group', { name: 'Filter swarm cases by status' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Gathering inputs' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('explains a synthesized thread as a recorded proposal, not an approval outcome', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        available: true,
        cases: [{
          caseId: 'case-17', caseClass: 'ALERT_REVIEW', dispositionTarget: 'operator', status: 'SYNTHESIZED',
          openedAtEpochMs: 1_700_000_000_000, deadlineAtEpochMs: 1_700_000_600_000, contestedRate: 0, contributionCount: 2,
        }],
      }),
    }))

    render(<IaopsCasesPage />)

    expect(await screen.findByText('A proposal is in the thread')).toBeInTheDocument()
    expect(screen.getByText('The thread records a proposal; delivery and the human decision are not shown here.')).toBeInTheDocument()
    expect(screen.queryByText('Synthesized')).not.toBeInTheDocument()
  })
})
