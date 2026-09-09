// SPDX-License-Identifier: Apache-2.0

import type { ReactNode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IncidentsPage from '@/app/security/incidents/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({ AuthGuard: ({ children }: { children: ReactNode }) => children }))

const incident = {
  id: 'ict-42', title: 'Payments unavailable', severity: 'HIGH', status: 'OPEN', category: 'AVAILABILITY',
  detectedAt: '2026-09-09T00:00:00Z', affectedServices: ['payments'], reportedToRegulator: false,
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('ICT incident register recovery', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('retains the last verified register through a failed refresh, then clears it if access is refused', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ available: true, incidents: [incident] }))
      .mockResolvedValueOnce(response({ message: 'failed' }, 500))
      .mockResolvedValueOnce(response({ available: false, reason: 'unauthorized' }))
    vi.stubGlobal('fetch', fetchMock)

    render(<LanguageProvider initialLanguage="en"><IncidentsPage /></LanguageProvider>)
    expect(await screen.findByText('Payments unavailable')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh ICT incidents' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('last successfully verified register remains below')
    expect(screen.getByText('Payments unavailable')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh ICT incidents' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Your role cannot view this register'))
    expect(screen.queryByText('Payments unavailable')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
