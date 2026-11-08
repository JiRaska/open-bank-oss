// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StatutoryReturnsPage from '@/app/regulatory/statutory-returns/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: { 'content-type': 'application/json' },
})

afterEach(() => vi.unstubAllGlobals())

describe('statutory returns read-only view', () => {
  it('shows real capability, breaches and revisions without exposing mutation actions', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/capability')) return response({
        dataSourceAvailable: false, wireFormatAvailable: false, note: 'Source not bound',
      })
      if (url.endsWith('/breaches')) return response([{ catalogueId: 'cz-pension-cnb', returnCode: 'PSP10-12-FUND',
        entityId: 'fund-a', period: '2026-09', dueDate: '2026-10-20', kind: 'NOT_ASSEMBLED' }])
      return response([{ id: '0bb2d245-c3b8-4bbb-9ea6-70f24f3732e7', catalogueId: 'cz-pension-cnb',
        returnCode: 'PSP10-12-FUND', entityId: 'fund-a', period: '2026-08', revision: 1,
        status: 'ASSEMBLED', dueDate: '2026-09-20', submittedAt: null, submissionReference: null }])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="cs"><StatutoryReturnsPage /></LanguageProvider>)

    expect(await screen.findByText('Source not bound')).toBeInTheDocument()
    expect(screen.getAllByText(/PSP10-12-FUND/)).toHaveLength(2)
    expect(screen.getByText(/NOT_ASSEMBLED/)).toBeInTheDocument()
    expect(screen.getByText(/Datový zdroj: nedostupný/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /schválit|odeslat|approve|submit/i })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.every(([url]) => url.startsWith('/api/svc/tax-reporting-service/api/v1/statutory-returns'))).toBe(true)
  })

  it('shows a service failure rather than invented empty returns', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ error: 'Unknown service: tax-reporting-service' }, 404)))
    render(<LanguageProvider initialLanguage="cs"><StatutoryReturnsPage /></LanguageProvider>)

    expect(await screen.findByText(/Tax-reporting-service není v tomto prostředí nasazená/)).toBeInTheDocument()
    expect(screen.queryByText('API nehlásí žádný překročený termín.')).not.toBeInTheDocument()
    expect(screen.queryByText('API zatím nevrátilo žádnou sestavenou revizi.')).not.toBeInTheDocument()
  })

  it('shows the CEÚ daily overview with synthetic exclusion counts and the ČNB answer, latest revision only', async () => {
    const ceu = (id: string, period: string, revision: number, status: string, extra: Record<string, unknown> = {}) => ({
      id, catalogueId: 'cz-ceu', returnCode: 'CEUSIFE10', entityId: 'company', period, revision, status,
      dueDate: '2026-11-10', submittedAt: null, submissionReference: null, acknowledgement: null,
      datapoints: { changes_total: 3, synthetic_accounts_dropped: 2, synthetic_relations_dropped: 1, initial_load: 0 },
      ...extra,
    })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/capability')) return response({ dataSourceAvailable: true, wireFormatAvailable: false, note: 'n' })
      if (url.endsWith('/breaches')) return response([])
      return response([
        ceu('11111111-1111-4111-8111-111111111111', '2026-11-09', 1, 'REJECTED', { acknowledgement: 'XSD error' }),
        ceu('22222222-2222-4222-8222-222222222222', '2026-11-09', 2, 'ACKNOWLEDGED',
          { submissionReference: 'SDAT-77', acknowledgement: 'CEU-ACK-1' }),
        ceu('33333333-3333-4333-8333-333333333333', '2026-11-08', 1, 'ASSEMBLED',
          { datapoints: { changes_total: 5, initial_load: 1 } }),
      ])
    }))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    const section = (await screen.findByRole('heading', { name: /CEÚ – daily change overview/ })).closest('section')!
    const rows = section.querySelectorAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('ACKNOWLEDGED')
    expect(rows[0].textContent).toContain('CEU-ACK-1')
    expect(rows[0].textContent).toContain('2 / 1')
    expect(rows[0].textContent).not.toContain('XSD error')
    expect(rows[1].textContent).toContain('initial full load')
    // An absent datapoint is shown as not reported, never as an invented zero.
    expect(rows[1].textContent).toContain('not reported / not reported')
    expect(screen.queryByRole('button', { name: /approve|submit|acknowledge/i })).not.toBeInTheDocument()
  })
})
