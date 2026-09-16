// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IncidentImpactInvestigation } from '@/components/context/IncidentImpactInvestigation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const incidents = [{
  id: '11111111-1111-4111-8111-111111111111',
  title: 'Synthetic incident',
  description: 'Synthetic evidence',
  category: 'AVAILABILITY',
  severity: 'P2_HIGH',
  status: 'OPEN',
  affectedServices: ['openbank-context-service'],
  detectedAt: '2026-09-14T06:00:00Z',
  reportedAt: '2026-09-14T06:01:00Z',
  containedAt: null,
  resolvedAt: null,
  rtoMinutes: null,
  rpoMinutes: null,
  reportedToRegulator: false,
  regulatoryReportId: null,
  assignedTo: null,
  createdAt: '2026-09-14T06:00:00Z',
  updatedAt: '2026-09-14T06:00:00Z',
}] as const

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

async function submit() {
  fireEvent.change(screen.getByLabelText('Incident'), { target: { value: incidents[0].id } })
  fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'case-7' } })
  fireEvent.click(screen.getByRole('button', { name: 'Evaluate impact' }))
}

describe('incident impact investigation', () => {
  it('renders a validated aggregate without exposing identifier drill-down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      affectedByType: { PAYMENT: 3, ACCOUNT: 1 }, total: 4, drilldownAvailable: false,
    }), { status: 200 })))
    render(<LanguageProvider initialLanguage="en"><IncidentImpactInvestigation incidents={[...incidents]} /></LanguageProvider>)

    await submit()

    expect(await screen.findByText('Total 4')).toBeInTheDocument()
    expect(screen.getByText('PAYMENT: 3')).toBeInTheDocument()
    expect(screen.getByText(/customer identifiers are never returned/)).toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it.each([
    { affectedByType: null, total: 'all', drilldownAvailable: false },
    { affectedByType: { PAYMENT: 3 }, total: 4, drilldownAvailable: false },
    { affectedByType: { PAYMENT: 3 }, total: 3, drilldownAvailable: true },
  ])('fails closed for malformed, inconsistent or drill-down aggregate evidence', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 })))
    render(<LanguageProvider initialLanguage="en"><IncidentImpactInvestigation incidents={[...incidents]} /></LanguageProvider>)

    await submit()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impact could not be verified safely.')
    expect(screen.queryByText(/Total /)).not.toBeInTheDocument()
  })
})
