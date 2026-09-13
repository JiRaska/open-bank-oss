// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Findings list page for the Flaky Test Hunter agent (ADR-0168, issue #5499).
//
//  - RBAC: the "Run check now" button (POST /api/iaops/flaky-test-hunter/trigger) must only render for
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
    window.localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
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

  it('shows only proven admission and never invents a current workflow state', async () => {
    mockRoles = [ROLES.ADMIN]
    const requestedOn = new Date().toISOString().slice(0, 10)
    const workflowId = `flaky-test-hunter-check-operator_manual-${requestedOn}`
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/iaops/flaky-test-hunter/trigger') {
        expect(init?.method).toBe('POST')
        return new Response(JSON.stringify({ workflowId }), {
          status: 202,
          headers: { 'content-type': 'application/json' },
        })
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

    await waitFor(() => expect(screen.getByText(
      `The request was admitted as workflow ${workflowId}. This does not prove whether the workflow is running or already complete.`,
    )).toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledWith('/api/iaops/flaky-test-hunter/trigger', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ requestedOn }),
    }))
    expect(screen.getByText('Accepted')).toBeInTheDocument()
    expect(screen.queryByText(/Check completed/i)).not.toBeInTheDocument()
    expect(trigger).toBeEnabled()
  })

  it('treats a timed-out admission as nonfinal and retains the idempotency day for retry', async () => {
    mockRoles = [ROLES.ADMIN]
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        return new Response(JSON.stringify({ error: 'admission_outcome_unknown' }), {
          status: 504,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => { renderPage() })
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })

    await waitFor(() => expect(screen.getByText(
      `Admission cannot be confirmed or ruled out. Retrying is safe: it reuses UTC key ${new Date().toISOString().slice(0, 10)}.`,
    )).toBeInTheDocument())
    expect(screen.queryByText(/Check completed/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Run check now/i })).toBeEnabled()
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBe(new Date().toISOString().slice(0, 10))
  })

  it('keeps the recovery key when an old backend replica cannot admit idempotently', async () => {
    mockRoles = [ROLES.ADMIN]
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        return new Response(JSON.stringify({ error: 'idempotent_admission_not_supported' }), {
          status: 503,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))

    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })

    const requestedOn = new Date().toISOString().slice(0, 10)
    await waitFor(() => expect(screen.getByText(
      `This backend replica does not support idempotent admission yet. This attempt did not start a workflow; the next retry reuses UTC key ${requestedOn}.`,
    )).toBeInTheDocument())
    expect(screen.queryByText('Accepted')).not.toBeInTheDocument()
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBe(requestedOn)
  })

  it.each([
    [
      'an unexpected 200 response',
      200,
      { workflowId: 'not-contracted' },
      'Admission cannot be confirmed or ruled out. Retrying is safe: it reuses UTC key',
    ],
    [
      'a malformed 202 response',
      202,
      {},
      'The workflow was admitted, but its identifier is missing. Retrying is safe: it reuses UTC key',
    ],
  ])('does not present %s as admitted or completed', async (_label, status, body, expected) => {
    mockRoles = [ROLES.ADMIN]
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        return new Response(JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => { renderPage() })
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })

    await waitFor(() => expect(screen.getByText(new RegExp(expected))).toBeInTheDocument())
    expect(screen.queryByText(/Check completed/i)).not.toBeInTheDocument()
    expect(screen.queryByText('Accepted')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Run check now/i })).toBeEnabled()
  })

  it('keeps an ambiguous request day across remount and reuses it for a safe recovery', async () => {
    mockRoles = [ROLES.ADMIN]
    const requestDays: string[] = []
    let attempts = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        requestDays.push((JSON.parse(String(init?.body)) as { requestedOn: string }).requestedOn)
        attempts += 1
        if (attempts === 1) throw new TypeError('connection lost after dispatch')
        return new Response(JSON.stringify({
          workflowId: `flaky-test-hunter-check-operator_manual-${requestDays.at(-1)}`,
        }), {
          status: 202,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const first = renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })
    await waitFor(() => expect(screen.getByText(/Admission cannot be confirmed or ruled out/i)).toBeInTheDocument())
    first.unmount()

    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })
    await waitFor(() => expect(screen.getByText(/The request was admitted as workflow flaky-test-hunter-check-operator_manual-/i)).toBeInTheDocument())

    expect(requestDays).toHaveLength(2)
    expect(requestDays[1]).toBe(requestDays[0])
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBeNull()
  })

  it('preserves a previous-day recovery key through reauthentication', async () => {
    mockRoles = [ROLES.ADMIN]
    const recoveryDate = new Date()
    recoveryDate.setUTCDate(recoveryDate.getUTCDate() - 1)
    const recoveryDay = recoveryDate.toISOString().slice(0, 10)
    window.localStorage.setItem('openbank.flaky-test-hunter.trigger.requested-on', recoveryDay)
    const requestDays: string[] = []
    let attempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        const requestedOn = (JSON.parse(String(init?.body)) as { requestedOn: string }).requestedOn
        requestDays.push(requestedOn)
        attempts += 1
        if (attempts === 1) {
          return new Response(JSON.stringify({ error: 'unauthorized' }), {
            status: 401,
            headers: { 'content-type': 'application/json' },
          })
        }
        return new Response(JSON.stringify({
          workflowId: `flaky-test-hunter-check-operator_manual-${requestedOn}`,
        }), {
          status: 202,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))

    const first = renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })
    await waitFor(() => expect(screen.getByText(/After access is restored, retry reuses UTC key/i)).toBeInTheDocument())
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBe(recoveryDay)
    first.unmount()

    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })
    await waitFor(() => expect(screen.getByText(/The request was admitted as workflow/i)).toBeInTheDocument())

    expect(requestDays).toEqual([recoveryDay, recoveryDay])
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBeNull()
  })

  it('distinguishes a known pre-admission rejection and leaves a corrected retry available', async () => {
    mockRoles = [ROLES.ADMIN]
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/iaops/flaky-test-hunter/trigger') {
        return new Response(JSON.stringify({ error: 'admission_rejected', upstreamStatus: 422 }), {
          status: 422,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ findings: [], available: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))

    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    await act(async () => { screen.getByRole('button', { name: /Run check now/i }).click() })

    await waitFor(() => expect(screen.getByText(
      'The request was rejected before admission (HTTP 422). It is safe to retry after correcting it.',
    )).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /Run check now/i })).toBeEnabled()
    expect(window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on')).toBeNull()
  })
})
