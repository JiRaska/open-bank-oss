// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StatutoryReturnsPage from '@/app/regulatory/statutory-returns/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { fireEvent, waitFor, within } from '@testing-library/react'

const session = vi.hoisted(() => ({ roles: ['ROLE_VIEWER'] as string[] }))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: session.roles } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: { 'content-type': 'application/json' },
})

afterEach(() => { vi.unstubAllGlobals(); session.roles = ['ROLE_VIEWER'] })

describe('statutory returns read-only view', () => {
  it('shows real capability, breaches and revisions without exposing mutation actions', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/capability')) return response({
        dataSourceAvailable: false, wireFormatAvailable: false, note: 'Source not bound',
      })
      if (url.endsWith('/catalogues')) return response([])
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
    expect(fetchMock).toHaveBeenCalledTimes(4)
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

  it('keeps the returns visible when only deadline monitoring is unavailable, and never reads it as "none missed"', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/capability')) return response({ dataSourceAvailable: true, wireFormatAvailable: false, note: 'Bound' })
      if (url.endsWith('/breaches')) return response({ error: 'Statutory return reporting start is not configured' }, 503)
      return response([])
    }))
    render(<LanguageProvider initialLanguage="cs"><StatutoryReturnsPage /></LanguageProvider>)

    expect(await screen.findByText(/Kontrolu termínů nelze vyhodnotit/)).toBeInTheDocument()
    expect(screen.getByText('API zatím nevrátilo žádnou sestavenou revizi.')).toBeInTheDocument()
    expect(screen.queryByText('API nehlásí žádný překročený termín.')).not.toBeInTheDocument()
  })

  it('reports the backend reason for an unknown deadline status and expects an empty CEÚ list before its start', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/capability')) return response({ dataSourceAvailable: true, wireFormatAvailable: true, note: 'n' })
      if (url.endsWith('/breaches')) return response({ error: 'fund roster is not configured' }, 503)
      if (url.endsWith('/catalogues')) return response([])
      return response([])
    }))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    expect(await screen.findByText(/Deadline status cannot be evaluated.*Service reason: fund roster is not configured/)).toBeInTheDocument()
    expect(screen.queryByText('The API reports no missed deadline.')).not.toBeInTheDocument()
    expect(screen.getByText(/CEÚ reporting starts on 2026-11-15/)).toBeInTheDocument()
  })
})

const catalogues = [
  { id: 'cz-pension-cnb', version: 1, jurisdiction: 'CZ', wireFormatVerified: false, returns: [
    { code: 'PSP10-12-FUND', name: 'Fund balance sheet', scope: 'FUND', periodicity: 'MONTH', legalBasis: 'Vyhláška 1', onDemand: false } ] },
  { id: 'cz-tax-information-exchange', version: 1, jurisdiction: 'CZ', wireFormatVerified: true, returns: [
    { code: 'CRS', name: 'CRS', scope: 'COMPANY', periodicity: 'YEAR', legalBasis: 'Zákon 164/2013', onDemand: false },
    { code: 'FATCA', name: 'FATCA', scope: 'COMPANY', periodicity: 'YEAR', legalBasis: 'FATCA IGA', onDemand: false } ] },
  { id: 'cz-ceu', version: 1, jurisdiction: 'CZ', wireFormatVerified: true, returns: [
    { code: 'CEUSIFE10', name: 'CEÚ', scope: 'COMPANY', periodicity: 'WORKING_DAY', legalBasis: '§ 41c', onDemand: false } ] },
  { id: 'cz-gsft', version: 1, jurisdiction: 'CZ', wireFormatVerified: true, returns: [
    { code: 'GSFT-DIW-TEST', name: 'GSFT test', scope: 'COMPANY', periodicity: 'DAY', legalBasis: '§ 41b', onDemand: true },
    { code: 'GSFT-DIW-LIVE', name: 'GSFT live', scope: 'COMPANY', periodicity: 'DAY', legalBasis: '§ 41b', onDemand: true } ] },
]

const revision = (status: string, extra: Record<string, unknown> = {}) => ({
  id: '0bb2d245-c3b8-4bbb-9ea6-70f24f3732e7', catalogueId: 'cz-gsft', returnCode: 'GSFT-DIW-LIVE', entityId: 'company',
  period: '2026-11-14', revision: 1, status, dueDate: '2026-11-21', submittedAt: null, submissionReference: null,
  assembledBy: 'maker-sub', ...extra,
})

function stub(returns: unknown[], post: (url: string, body: unknown) => Response) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') return post(url, init.body ? JSON.parse(String(init.body)) : undefined)
    if (url.endsWith('/capability')) return response({ dataSourceAvailable: true, wireFormatAvailable: true, note: 'n' })
    if (url.endsWith('/breaches')) return response([])
    if (url.endsWith('/catalogues')) return response(catalogues)
    return response(returns)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('statutory returns catalogue and operator lifecycle', () => {
  it('lists every catalogue and code, with "not assembled yet" where no revision exists', async () => {
    stub([revision('ASSEMBLED')], () => response({}, 500))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    const live = await screen.findByTestId('return-cz-gsft-GSFT-DIW-LIVE')
    expect(live.textContent).toContain('2026-11-14 · revision 1 · ASSEMBLED')
    expect(live.textContent).toContain('yes')
    for (const code of ['cz-pension-cnb-PSP10-12-FUND', 'cz-tax-information-exchange-CRS', 'cz-tax-information-exchange-FATCA', 'cz-ceu-CEUSIFE10', 'cz-gsft-GSFT-DIW-TEST']) {
      expect(screen.getByTestId(`return-${code}`).textContent).toContain('not assembled yet')
    }
    expect(screen.getByTestId('return-cz-tax-information-exchange-CRS').textContent).toContain('Zákon 164/2013')
    // A viewer sees everything and can act on nothing.
    expect(screen.queryByRole('button', { name: /assemble|approve/i })).not.toBeInTheDocument()
  })

  it('does not offer lifecycle actions to ADMIN, whom the backend refuses', async () => {
    session.roles = ['ROLE_ADMIN']
    stub([revision('ASSEMBLED')], () => response({}, 500))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)
    await screen.findByTestId('return-cz-gsft-GSFT-DIW-LIVE')
    expect(screen.queryByRole('button', { name: /assemble|approve/i })).not.toBeInTheDocument()
  })

  it('assembles a return with the AssembleReturnRequest body and shows a 503 refusal verbatim', async () => {
    session.roles = ['ROLE_OPERATOR']
    const fetchMock = stub([], () => response({ error: 'No data source is bound for CRS' }, 503))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    const form = await screen.findByRole('form', { name: 'Assemble a return' })
    fireEvent.change(within(form).getByRole('combobox'), { target: { value: 'cz-tax-information-exchange/CRS' } })
    fireEvent.change(within(form).getByLabelText('Period'), { target: { value: '2025' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Assemble' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not configured): No data source is bound for CRS')
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(post[0]).toBe('/api/svc/tax-reporting-service/api/v1/statutory-returns/assemble')
    expect(JSON.parse(String(post[1]!.body))).toEqual({ catalogueId: 'cz-tax-information-exchange', returnCode: 'CRS', entityId: 'company', period: '2025' })
  })

  it('shows a four-eyes 409 on approve instead of pretending it worked', async () => {
    session.roles = ['ROLE_OPERATOR']
    stub([revision('ASSEMBLED')], url => url.endsWith('/approve')
      ? response({ error: 'The assembler may not approve' }, 409) : response({}, 500))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('four-eyes rule: The assembler may not approve')
  })

  it('delivers an approved GSFT batch through /gsft-delivery, never /submitted', async () => {
    session.roles = ['ROLE_OPERATOR']
    const fetchMock = stub([revision('APPROVED')], url => url.endsWith('/gsft-delivery')
      ? response({ statutoryReturn: revision('SUBMITTED', { submissionReference: 'B-1' }), fileName: 'x.zip.pgp', encryptedPackage: 'AA==' })
      : response({}, 500))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    fireEvent.change(await screen.findByLabelText('Submission reference'), { target: { value: 'B-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Encrypt and deliver to GSFT' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Done: GSFT-DIW-LIVE · 2026-11-14 · SUBMITTED'))
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0][0]).toBe('/api/svc/tax-reporting-service/api/v1/statutory-returns/0bb2d245-c3b8-4bbb-9ea6-70f24f3732e7/gsft-delivery')
    expect(JSON.parse(String(posts[0][1]!.body))).toEqual({ reference: 'B-1' })
  })

  it('records a non-GSFT submission and a regulator rejection with the right bodies', async () => {
    session.roles = ['ROLE_OPERATOR']
    const crs = { catalogueId: 'cz-tax-information-exchange', returnCode: 'CRS', period: '2025' }
    const fetchMock = stub([revision('SUBMITTED', crs)], () => response(revision('REJECTED', crs)))
    render(<LanguageProvider initialLanguage="en"><StatutoryReturnsPage /></LanguageProvider>)

    fireEvent.change(await screen.findByLabelText('Acknowledgement reference or rejection reason'), { target: { value: 'XSD error' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rejected' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(post[0]).toMatch(/\/acknowledgement$/)
    expect(JSON.parse(String(post[1]!.body))).toEqual({ accepted: false, reason: 'XSD error' })
  })
})
