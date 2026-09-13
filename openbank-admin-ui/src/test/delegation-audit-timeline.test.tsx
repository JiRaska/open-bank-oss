// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DelegationAuditTimeline } from '@/components/delegations/DelegationAuditTimeline'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { DelegationAuditTimelineResponse } from '@/lib/delegations/auditTimeline'

const GRANT = '018f4a3c-1b2d-7e00-9a11-000000000002'
const EVIDENCE = '018f4a3c-1b2d-7e00-9a11-000000000101'

function response(overrides: Partial<DelegationAuditTimelineResponse> = {}): DelegationAuditTimelineResponse {
  return {
    grantId: GRANT,
    latestStatusAfter: 'SUSPENDED',
    mayBeTruncated: false,
    entries: [{
      evidenceId: EVIDENCE,
      eventType: 'DelegationSuspended',
      occurredAt: '2026-08-31T11:00:00.000Z',
      recordedAt: '2026-08-31T11:00:01.000Z',
      timeSource: 'event',
      actorId: null,
      actorType: null,
      actorProvenance: 'absent',
      reason: 'Fraud review',
      reasonState: 'recorded',
      reasonTruncated: false,
      statusAfter: 'SUSPENDED',
      sourceService: 'delegation-service',
      sourceAttribution: 'event',
      correlationId: 'correlation-1',
    }],
    ...overrides,
  }
}

function mount(currentStatus = 'SUSPENDED') {
  return render(
    <LanguageProvider>
      <DelegationAuditTimeline grantId={GRANT} currentStatus={currentStatus} />
    </LanguageProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('openbank-admin-lang', 'en')
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('delegation immutable audit timeline', () => {
  it('explains action, actor absence, reason, status and evidence provenance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify(response()), { status: 200 }),
    ))
    mount()

    expect(screen.getByRole('status')).toHaveTextContent('Loading audit evidence')
    expect(await screen.findByText('Delegation suspended')).toBeVisible()
    expect(screen.getByText(/Live status/)).toHaveTextContent('matches the latest audited transition')
    expect(screen.getByText('Actor not recorded in the event')).toBeVisible()
    expect(screen.getByText(/Fraud review/)).toBeVisible()
    expect(screen.getByText(EVIDENCE)).toBeInTheDocument()
    expect(screen.getByText('producer event time')).toBeInTheDocument()
    expect(screen.getByText('delegation-service · producer-declared')).toBeInTheDocument()
  })

  it('warns rather than claiming consistency when live and audited statuses differ', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify(response()), { status: 200 }),
    ))
    mount('ACTIVE')

    expect(await screen.findByText(/Live status is/)).toHaveTextContent('latest audited transition leads to SUSPENDED')
  })

  it('distinguishes an authorized empty history from denied audit access', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify(response({ entries: [], latestStatusAfter: null })), { status: 200 }),
    ))
    const view = mount()
    expect(await screen.findByText(/Audit-service answered successfully but has no event/)).toBeVisible()

    view.unmount()
    vi.mocked(global.fetch).mockResolvedValueOnce(new Response('{}', { status: 403 }))
    mount()
    expect(await screen.findByText('Audit evidence is restricted to oversight roles')).toBeVisible()
    expect(screen.getByText(/This is not an empty history/)).toBeVisible()
  })

  it('retains the last successful snapshot when a refresh is temporarily unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(response()), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'upstream_unreachable' }), { status: 502 })))
    mount()
    expect(await screen.findByText('Delegation suspended')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh audit timeline' }))
    expect(await screen.findByText(/last successfully loaded snapshot remains visible/)).toBeVisible()
    expect(screen.getByText('Delegation suspended')).toBeVisible()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh audit timeline' })).not.toBeDisabled())
  })
})
