// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
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

    expect(await screen.findByText('Observed 4')).toBeInTheDocument()
    expect(screen.getByText('PAYMENT: 3')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Incident timeline' })).toHaveTextContent('Detected')
    expect(screen.getByText('A time window alone does not prove impact on a customer or business case.')).toBeInTheDocument()
    expect(screen.getByText(/customer identifiers are never returned/)).toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it('marks an inconsistent legacy source window unknown after authorization', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      affectedByType: { SERVICE: 1 }, total: 1, drilldownAvailable: false, projectionStatus: 'AVAILABLE',
    }))))
    const inconsistent = { ...incidents[0], containedAt: '2026-09-13T06:00:00Z' }
    render(<LanguageProvider initialLanguage="en"><IncidentImpactInvestigation incidents={[inconsistent]} /></LanguageProvider>)

    await submit()

    expect(await screen.findByText('Source timestamps conflict; the incident window is unknown.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Incident timeline' })).not.toBeInTheDocument()
  })

  it('discards a late aggregate after the selected incident changes', async () => {
    let resolve!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(done => { resolve = done })))
    render(<LanguageProvider initialLanguage="en"><IncidentImpactInvestigation incidents={[...incidents]} /></LanguageProvider>)
    await submit()
    fireEvent.change(screen.getByLabelText('Incident'), { target: { value: '' } })
    await act(async () => { resolve(new Response(JSON.stringify({
      affectedByType: { SERVICE: 2 }, total: 2, drilldownAvailable: false, projectionStatus: 'AVAILABLE',
    }))) })
    expect(screen.queryByText('Observed 2')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Aggregate incident impact map' })).not.toBeInTheDocument()
  })

  it.each([
    ['MISSING', {}, 0, 'Impact is unknown.'],
    ['PARTIAL', { SERVICE: 2 }, 2, 'counts cover only the retrieved slice'],
    ['UNKNOWN', {}, 0, 'does not report projection completeness'],
  ])('reports %s without implying an all-clear', async (projectionStatus, affectedByType, total, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      affectedByType, total, drilldownAvailable: false, projectionStatus,
    }))))
    render(<LanguageProvider initialLanguage="en"><IncidentImpactInvestigation incidents={[...incidents]} /></LanguageProvider>)
    await submit()
    expect(await screen.findByRole('status')).toHaveTextContent(String(message))
    if (projectionStatus === 'MISSING') expect(screen.queryByText('Observed 0')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'another-case' } })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Aggregate incident impact map' })).not.toBeInTheDocument()
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
    expect(screen.queryByText(/Observed /)).not.toBeInTheDocument()
  })
})
