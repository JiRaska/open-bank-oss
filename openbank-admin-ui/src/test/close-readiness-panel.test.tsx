// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { CloseReadinessPanel, parseCloseReadiness } from '@/components/closings/CloseReadinessPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const evidence = {
  month: '2024-02', observedDays: 29, requiredDays: 29, eligibleForEnforcement: false,
  blockers: ['INCOMPLETE_DAILY_EVIDENCE'],
  days: Array.from({ length: 29 }, (_, n) => ({
    businessDate: `2024-02-${String(n + 1).padStart(2, '0')}`,
    blockers: n === 0 ? ['MISSING:billing'] : [],
  })),
}

afterEach(() => vi.unstubAllGlobals())

it('shows first-day missing fees despite an otherwise observed full month', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(evidence))))
  render(<LanguageProvider><CloseReadinessPanel /></LanguageProvider>)
  expect(await screen.findByText(/does not yet support activating/)).toBeInTheDocument()
  expect(screen.getByText(/Missing report: billing/)).toBeInTheDocument()
  expect(screen.getByText(/Late reports cannot repair historical evidence/)).toBeInTheDocument()
})

it('clears a previous positive verdict when refreshing becomes unavailable', async () => {
  const ready = { ...evidence, eligibleForEnforcement: true, blockers: [], days: evidence.days.map(d => ({ ...d, blockers: [] })) }
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(ready)))
    .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'upstream_unreachable' }), { status: 502 })))
  render(<LanguageProvider><CloseReadinessPanel /></LanguageProvider>)
  expect(await screen.findByText(/Monthly evidence is complete/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh monthly evidence' }))
  expect(await screen.findByText(/Ledger-service is not responding/)).toBeInTheDocument()
  expect(screen.queryByText(/Monthly evidence is complete/)).not.toBeInTheDocument()
})

it('rejects malformed and contradictory positive evidence', () => {
  expect(parseCloseReadiness(evidence)).not.toBeNull()
  expect(parseCloseReadiness({ ...evidence, eligibleForEnforcement: true })).toBeNull()
  expect(parseCloseReadiness({ ...evidence, requiredDays: 0, days: [] })).toBeNull()
  expect(parseCloseReadiness({ ...evidence, observedDays: -1 })).toBeNull()
  expect(parseCloseReadiness({ ...evidence, days: [] })).toBeNull()
})
