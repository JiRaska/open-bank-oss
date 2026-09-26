// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import InfrastructurePage from '@/app/infrastructure/page'

const json = (body: unknown, status = 200) => Response.json(body, { status })

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('infrastructure snapshot recovery', () => {
  it('keeps the last verified health evidence when refresh fails', async () => {
    let statusCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/infra/status')) {
        statusCalls += 1
        return statusCalls === 1
          ? json({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } })
          : json({ error: 'unavailable' }, 503)
      }
      return json({ error: 'unavailable' }, 503)
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)

    const postgres = await screen.findByText('PostgreSQL')
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))

    await waitFor(() => expect(screen.getByText(/last verified infrastructure snapshot/i)).toBeInTheDocument())
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
  })

  it('rejects malformed refresh evidence without replacing the verified snapshot', async () => {
    let statusCalls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (!String(input).endsWith('/api/infra/status')) return json({ error: 'unavailable' }, 503)
      statusCalls += 1
      return statusCalls === 1
        ? json({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } })
        : json({ postgres: { id: 'postgres', status: 'HEALTHY', latencyMs: 1, checkedAt: '2026-09-09T08:01:00Z' } })
    }))

    render(<LanguageProvider><InfrastructurePage /></LanguageProvider>)
    const postgres = await screen.findByText('PostgreSQL')
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))

    await waitFor(() => expect(screen.getByText(/last verified infrastructure snapshot/i)).toBeInTheDocument())
    expect(within(postgres.closest('.card') as HTMLElement).getByText('UP')).toBeInTheDocument()
  })
})
