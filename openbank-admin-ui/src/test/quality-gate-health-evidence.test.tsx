// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { QualityGateHealthPanel } from '@/components/devops/QualityGateHealthPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const observedAt = '2026-10-19T08:00:00.000Z'
const snapshot = (evidence?: unknown) => ({
  available: true,
  source: 'github-actions',
  collectedAt: observedAt,
  runsInspected: 12,
  gateDetailRunsInspected: 8,
  shardHistory: [{ runId: 42, sha: 'a'.repeat(40), event: 'push', createdAt: observedAt, shards: [{ name: 'unit', conclusion: 'success', seconds: 45 }] }],
  gates: [{ id: 'unit-tests', group: 'quality', mode: 'enforced', status: 'passing', lastRed: { runId: 40, sha: 'a'.repeat(40), createdAt: observedAt }, flaky: false, selftestDeclared: true, minSubjects: 10, budgetSeconds: 60, runsObserved: 12 }],
  estate: { total: 1, enforced: 1, advisory: 0, withSelftest: 1, withFloor: 1, withBudget: 1, flaky: 0 },
  ...(evidence ? { evidence } : {}),
})
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const renderPanel = () => render(<LanguageProvider initialLanguage="en"><QualityGateHealthPanel /></LanguageProvider>)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('QualityGateHealthPanel evidence states', () => {
  it.each([401, 503])('shows unavailable evidence after HTTP %s without inventing a healthy estate', async status => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ error: 'denied' }, status)))
    renderPanel()

    expect(await screen.findByText(/Quality-gate evidence is unavailable/)).toBeVisible()
    expect(screen.queryByText('denied')).not.toBeInTheDocument()
    expect(screen.queryByText('Total gates')).not.toBeInTheDocument()
    expect(screen.queryByText('1')).not.toBeInTheDocument()
  })

  it('retains the last good rows after a failed refresh, labels them stale, then recovers', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(snapshot()))
      .mockResolvedValueOnce(response({ error: 'temporarily unavailable' }, 503))
      .mockResolvedValueOnce(response(snapshot()))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    expect(await screen.findByText('Total gates')).toBeVisible()
    expect(screen.getByText('unit-tests')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText(/Showing the last successfully loaded snapshot/)).toBeVisible()
    expect(screen.queryByText('temporarily unavailable')).not.toBeInTheDocument()
    expect(screen.getByText('unit-tests')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(screen.queryByText(/Showing the last successfully loaded snapshot/)).not.toBeInTheDocument())
    expect(screen.getByText('unit-tests')).toBeVisible()
  })

  it('renders runtime provenance and the last successful observation time', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(snapshot({
      state: 'ready',
      provenance: {
        collectedAt: observedAt,
        expiresAt: '2026-10-19T09:00:00.000Z',
        sourceSha: 'b'.repeat(40),
        runId: 1234,
        runAttempt: 2,
      },
    }))))
    renderPanel()

    expect(await screen.findByText(/Last successful observation/)).toBeVisible()
    expect(screen.getByText(/source bbbbbbbb/)).toBeVisible()
    expect(screen.getByText(new Date(observedAt).toLocaleString('en-GB'), { exact: true })).toBeVisible()
    expect(screen.getByText(new Date(observedAt).toLocaleString('en-GB'), { exact: true })).toHaveAttribute('datetime', observedAt)
    expect(screen.queryByText(/collected at build time/i)).not.toBeInTheDocument()
  })

  it('rejects an available response with missing required evidence arrays', async () => {
    const malformed = { ...snapshot(), gates: undefined, shardHistory: undefined }
    vi.stubGlobal('fetch', vi.fn(async () => response(malformed)))
    renderPanel()

    expect(await screen.findByText(/Quality-gate evidence is unavailable/)).toBeVisible()
    expect(screen.queryByText('Total gates')).not.toBeInTheDocument()
  })
})
