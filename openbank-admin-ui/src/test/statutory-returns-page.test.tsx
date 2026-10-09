// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StatutoryReturnsPage from '@/app/regulatory/statutory-returns/page'

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
    render(<StatutoryReturnsPage />)

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
    render(<StatutoryReturnsPage />)

    expect(await screen.findByText(/Tax-reporting-service není v tomto prostředí nasazená/)).toBeInTheDocument()
    expect(screen.queryByText('API nehlásí žádný překročený termín.')).not.toBeInTheDocument()
    expect(screen.queryByText('API zatím nevrátilo žádnou sestavenou revizi.')).not.toBeInTheDocument()
  })
})
