// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IncidentsPage from '@/app/security/incidents/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => children,
}))

// Every field `incidentEvidence.parseIncident` requires — it rejects the whole register if any
// row is incomplete, so a partial fixture makes the page render empty and the assertions below
// fail for a reason that has nothing to do with what they test.
const INCIDENT = {
  id: '128cdb0e-04ae-4a35-8af1-ccca692ec413',
  title: 'Payment rail unavailable',
  description: 'The SEPA payment rail stopped accepting instructions.',
  category: 'AVAILABILITY',
  severity: 'P1_CRITICAL',
  status: 'INVESTIGATING',
  affectedServices: ['payment-service'],
  detectedAt: '2026-09-10T01:23:45Z',
  reportedAt: '2026-09-10T01:30:00Z',
  containedAt: null,
  resolvedAt: null,
  rtoMinutes: null,
  rpoMinutes: null,
  reportedToRegulator: false,
  regulatoryReportId: null,
  assignedTo: null,
  createdAt: '2026-09-10T01:23:45Z',
  updatedAt: '2026-09-10T01:30:00Z',
}

const response = (body: unknown) => new Response(JSON.stringify(body), {
  status: 200,
  headers: { 'content-type': 'application/json' },
})

async function renderPage() {
  await act(async () => { render(<LanguageProvider initialLanguage="en"><IncidentsPage /></LanguageProvider>) })
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('ICT incident register recovery', () => {
  it('retains verified evidence after an invalid refresh and labels it stale', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response({ available: true, incidents: [INCIDENT] }))
      .mockResolvedValueOnce(response({ available: true, incidents: [{ ...INCIDENT, severity: 'EXTREME' }] })))

    await renderPage()
    expect(await screen.findByText(INCIDENT.title)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh ICT incidents' }))

    expect(await screen.findByText('Showing the last successfully verified snapshot.')).toBeVisible()
    expect(screen.getByText(INCIDENT.title)).toBeVisible()
  })

  it('purges retained incident evidence when the session is refused', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response({ available: true, incidents: [INCIDENT] }))
      .mockResolvedValueOnce(response({ available: false, reason: 'unauthorized' })))

    await renderPage()
    expect(await screen.findByText(INCIDENT.title)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh ICT incidents' }))

    // main words the unauthorized banner as a verification failure plus the specific remedy; the
    // assertion that matters is the one below — retained evidence is PURGED, not kept visible.
    expect(await screen.findByRole('alert')).toHaveTextContent('The register could not be verified')
    expect(screen.queryByText(INCIDENT.title)).not.toBeInTheDocument()
    expect(screen.queryByText('Showing the last successfully verified snapshot.')).not.toBeInTheDocument()
  })

  it('aborts an in-flight evidence read when the protected screen unmounts', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise<Response>(() => undefined)
    }))

    let view!: ReturnType<typeof render>
    await act(async () => { view = render(<LanguageProvider initialLanguage="en"><IncidentsPage /></LanguageProvider>) })
    // The page defers its first load with setTimeout(…, 0) — a MACROtask, which `act` does not
    // flush. Wait for the request to actually be in flight before asserting anything about its
    // signal, or `signal` is still undefined and both assertions below are vacuous.
    await waitFor(() => expect(signal).toBeDefined())
    expect(signal?.aborted).toBe(false)
    view.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
