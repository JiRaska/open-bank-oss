// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IncidentsPage from '@/app/security/incidents/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => children,
}))

const INCIDENT = {
  id: '128cdb0e-04ae-4a35-8af1-ccca692ec413',
  title: 'Payment rail unavailable',
  category: 'AVAILABILITY',
  severity: 'P1_CRITICAL',
  status: 'INVESTIGATING',
  detectedAt: '2026-09-10T01:23:45Z',
  affectedServices: ['payment-service'],
  reportedToRegulator: false,
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

    expect(await screen.findByText('Showing the last verified snapshot')).toBeVisible()
    expect(screen.getByText(INCIDENT.title)).toBeVisible()
  })

  it('purges retained incident evidence when the session is refused', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response({ available: true, incidents: [INCIDENT] }))
      .mockResolvedValueOnce(response({ available: false, reason: 'unauthorized' })))

    await renderPage()
    expect(await screen.findByText(INCIDENT.title)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh ICT incidents' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Incident register unavailable')
    expect(screen.queryByText(INCIDENT.title)).not.toBeInTheDocument()
    expect(screen.queryByText('Showing the last verified snapshot')).not.toBeInTheDocument()
  })

  it('aborts an in-flight evidence read when the protected screen unmounts', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
      signal = init?.signal ?? undefined
      return new Promise<Response>(() => undefined)
    }))

    let view!: ReturnType<typeof render>
    await act(async () => { view = render(<LanguageProvider initialLanguage="en"><IncidentsPage /></LanguageProvider>) })
    expect(signal?.aborted).toBe(false)
    view.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
