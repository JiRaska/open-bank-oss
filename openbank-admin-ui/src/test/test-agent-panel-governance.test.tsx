// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TestAgentPanel } from '@/components/testing/TestAgentPanel'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_VIEWER'] } }, status: 'authenticated' }),
}))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Test Agent governance evidence', () => {
  it('keeps a missing eval visible when the runtime agent is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      findings: [],
      available: false,
      governance: { activePrompt: 'system.v2', evalEvidence: 'missing-suite' },
    }), { status: 200, headers: { 'content-type': 'application/json' } })))

    render(<TestAgentPanel />)

    expect(await screen.findByText('system.v2')).toBeVisible()
    expect(screen.getByText('missing-suite')).toBeVisible()
    expect(screen.getByText('No eval suite is registered for this charter. The agent remains advisory, never an automation authority.')).toBeVisible()
    expect(screen.getByRole('link', { name: /Open evaluation backlog/ })).toHaveAttribute('href', 'https://github.com/JiRaska/open-bank-oss/issues/7040')
    expect(screen.getByText(/agent is unavailable/i)).toBeVisible()
  })

  it('prioritizes critical findings before limiting the visible list', async () => {
    const newerWarnings = Array.from({ length: 5 }, (_, index) => ({
      id: `warning-${index + 1}`,
      checkType: 'flaky-test',
      severity: 'WARNING',
      detectedAt: `2026-08-0${6 - index}T10:00:00Z`,
      title: `Newer warning ${index + 1}`,
      component: 'openbank-test-service',
      rootCause: null,
      proposalUrl: null,
      status: 'OPEN',
    }))
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      findings: [
        ...newerWarnings,
        {
          id: 'critical-older',
          checkType: 'flaky-test',
          severity: 'CRITICAL',
          detectedAt: '2026-08-01T10:00:00Z',
          title: 'Older critical finding',
          component: 'openbank-critical-service',
          rootCause: null,
          proposalUrl: null,
          status: 'OPEN',
        },
      ],
      available: true,
    }), { status: 200, headers: { 'content-type': 'application/json' } })))

    render(<TestAgentPanel />)

    expect(await screen.findByText(/Older critical finding/)).toBeVisible()
    expect(screen.getAllByText(/^(CRITICAL|WARNING)$/).map(item => item.textContent)).toEqual([
      'CRITICAL', 'WARNING', 'WARNING', 'WARNING', 'WARNING',
    ])
    expect(screen.queryByText(/Newer warning 5/)).not.toBeInTheDocument()
    const truncationStatus = screen.getByRole('status')
    expect(truncationStatus).toHaveTextContent('Showing 5 of 6 findings.')
    expect(truncationStatus).toBeVisible()
  })
})
