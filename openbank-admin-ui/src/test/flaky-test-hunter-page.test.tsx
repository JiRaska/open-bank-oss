// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Findings list page for the Flaky Test Hunter agent (ADR-0168, issue #5499).
//
//  - RBAC: the "Run check now" button (POST /api/flaky-test-hunter/trigger) must only render for
//    a role admitted to 'flaky-test-hunter:trigger' — mirrors FlakyTestResource's own
//    @RolesAllowed("ROLE_ADMIN") on POST /check/trigger. A ROLE_OPERATOR or ROLE_VIEWER session
//    must never see it, even though both can view the findings list itself.
//  - Rendering: the findings table surfaces severity, check type and component per row — the
//    columns the issue calls out as the minimum.

import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createElement } from 'react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { ROLES } from '@/lib/auth/roles'

let mockRoles: string[] = []
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: mockRoles, accessToken: 'test-token' } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

import FlakyTestHunterPage from '@/app/iaops/flaky-test-hunter/page'

const FINDING = {
  id: 'finding-1',
  checkType: 'RUNBLOCKING_UNIT_MISSING',
  severity: 'CRITICAL',
  detectedAt: '2026-08-10T06:30:00Z',
  title: 'runBlocking silently dropped in KycRetentionSchedulerTest',
  component: 'openbank-kyc-service',
  filePath: 'openbank-kyc-service/src/test/.../KycRetentionSchedulerTest.kt',
  rawMetricValue: 1,
  threshold: 0,
  rootCause: null,
  proposalUrl: null,
  proposedFixDiff: null,
  status: 'OPEN',
  diagnosedAt: null,
  proposedAt: null,
}

function renderPage() {
  return render(createElement(LanguageProvider, null, createElement(FlakyTestHunterPage)))
}

describe('Flaky Test Hunter findings page', () => {
  beforeEach(() => {
    mockRoles = []
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    cleanup()
  })

  it('renders findings with severity, check type and component columns', async () => {
    mockRoles = [ROLES.OPERATOR]
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ findings: [FINDING], available: true }), {
        status: 200, headers: { 'content-type': 'application/json' },
      }),
    ))

    await act(async () => { renderPage() })

    await waitFor(() => expect(screen.getByText(FINDING.title)).toBeInTheDocument())
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
    expect(screen.getByText('runBlocking missing Unit')).toBeInTheDocument()
    expect(screen.getByText(FINDING.component)).toBeInTheDocument()
  })

  it('hides the "Run check now" trigger for a role without flaky-test-hunter:trigger (RBAC gate)', async () => {
    mockRoles = [ROLES.OPERATOR]
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200, headers: { 'content-type': 'application/json' },
      }),
    ))

    await act(async () => { renderPage() })

    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /Run check now/i })).not.toBeInTheDocument()
  })

  it('shows the trigger for ROLE_ADMIN and POSTs to the trigger BFF route on click', async () => {
    mockRoles = [ROLES.ADMIN]
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/flaky-test-hunter/trigger')) {
        expect(init?.method).toBe('POST')
        return new Response(JSON.stringify({
          runId: 'run-1', startedAt: '2026-08-16T06:30:00Z', completedAt: '2026-08-16T06:31:00Z',
          testFilesScanned: 120, findingsDetected: [], findingsProposed: 0, tokensUsed: 0, trigger: 'OPERATOR_MANUAL',
        }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200, headers: { 'content-type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => { renderPage() })
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())

    const trigger = screen.getByRole('button', { name: /Run check now/i })
    expect(trigger).toBeInTheDocument()
    await act(async () => { trigger.click() })

    await waitFor(() => expect(fetchMock.mock.calls.some(c => String(c[0]).includes('/api/flaky-test-hunter/trigger'))).toBe(true))
  })
})
