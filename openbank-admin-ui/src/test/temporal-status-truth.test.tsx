// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { ROLES } from '@/lib/auth/roles'

let mockRoles: string[] = []
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: mockRoles } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

import TemporalPage from '@/app/temporal/page'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  mockRoles = []
})

describe('Temporal deployment status presentation', () => {
  it('shows unknown in the main-page badge when Prometheus cannot confirm deployment', async () => {
    mockRoles = [ROLES.ADMIN]
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      available: true,
      temporalDeployed: null,
      metrics: null,
    }), { status: 200, headers: { 'content-type': 'application/json' } })))

    render(<LanguageProvider initialLanguage="en"><TemporalPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('Temporal deployment status unknown')).toBeVisible())
    fireEvent.click(screen.getByRole('button', { name: 'Metrics' }))
    expect(screen.queryByText('Temporal cannot be confirmed from Prometheus')).not.toBeInTheDocument()
    expect(screen.queryByText(/Workers: active/)).not.toBeInTheDocument()
  })

  it('warns when Temporal is detected but some metric queries failed', async () => {
    mockRoles = [ROLES.ADMIN]
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      available: true,
      temporalDeployed: true,
      degraded: true,
      metrics: {
        workflows: { scheduled1h: 4, completed1h: 2, failed1h: null, timedOut1h: 0 },
        latency: { activityScheduleToStartMs: null, workflowTaskScheduleToStartMs: null, serverRequestP99Ms: null },
        persistence: { requestsPerSec: null },
        workers: { totalSlotsAvailable: null, slotsUsed: null },
        namespaces: [],
        workflowTypes: null,
      },
    }), { status: 200, headers: { 'content-type': 'application/json' } })))

    render(<LanguageProvider initialLanguage="en"><TemporalPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('Temporal metrics partially available')).toBeVisible())
    expect(screen.queryByText('Temporal metrics observed')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Metrics' }))
    expect(screen.getByText('Some metric queries failed. Unverified values are shown as unavailable.')).toBeVisible()
  })
})
