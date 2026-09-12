// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { AUDIT_EVIDENCE_WINDOW } from '@/lib/audit/auditEvidence'
import AuditPage from '@/app/audit/page'

const AGGREGATE = '11111111-1111-4111-8111-111111111111'

function entry(n: number) {
  return {
    id: `entry-${n}`,
    aggregateId: AGGREGATE,
    aggregateType: 'ACCOUNT',
    eventType: 'CREATED',
    payload: '{}',
    sourceService: 'openbank-account-service',
    occurredAt: '2026-09-01T10:00:00Z',
    recordedAt: '2026-09-01T10:00:01Z',
    occurredAtSource: 'EVENT',
    sourceServiceSource: 'EVENT',
    actChain: [],
  }
}

function respondWith(count: number) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(
    JSON.stringify(Array.from({ length: count }, (_, i) => entry(i))),
    { status: 200, headers: { 'content-type': 'application/json' } },
  ))
  vi.stubGlobal('fetch', fetchMock)
  render(React.createElement(LanguageProvider, null, React.createElement(AuditPage)))
  fireEvent.change(screen.getByLabelText('Aggregate ID'), { target: { value: AGGREGATE } })
  fireEvent.click(screen.getByRole('button', { name: 'Search audit trail' }))
  return fetchMock
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('audit trail evidence window', () => {
  // The service clamps at 500 (AuditResource: `limit.coerceIn(1, 500)`); the page used to ask for
  // 100 and call that the maximum, so four fifths of the retrievable trail were unreachable and
  // the shortfall was undisclosed.
  it('requests the whole service-supported window, not a smaller hard-coded page', async () => {
    const fetchMock = respondWith(3)
    await screen.findByText(AGGREGATE)

    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toContain(`limit=${AUDIT_EVIDENCE_WINDOW}`)
    expect(AUDIT_EVIDENCE_WINDOW).toBe(500)
    // Negative case: the old bound must not survive anywhere in the request.
    expect(url).not.toContain('limit=100')
  })

  it('states the trail is complete when the service returned fewer entries than the window', async () => {
    respondWith(3)
    await screen.findByText(AGGREGATE)

    expect(screen.getByText(/All 3 events the service returned/)).toBeTruthy()
    expect(screen.queryByText(/are not shown/)).toBeNull()
  })

  it('discloses truncation when the response fills the window exactly', async () => {
    respondWith(AUDIT_EVIDENCE_WINDOW)
    await screen.findByText(AGGREGATE)

    const disclosure = screen.getByText(/the window is full/)
    expect(disclosure.textContent).toContain(`${AUDIT_EVIDENCE_WINDOW} maximum per query`)
    expect(disclosure.textContent).toContain('older events may exist and are not shown')
  })

  it('does not promise a full trail in the search hint', async () => {
    respondWith(1)
    await screen.findByText(AGGREGATE)

    expect(screen.queryByText(/full audit trail/)).toBeNull()
    expect(screen.getByText(new RegExp(`at most the ${AUDIT_EVIDENCE_WINDOW} newest events`))).toBeTruthy()
  })
})
