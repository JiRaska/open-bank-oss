// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IAOpsPage from '@/app/iaops/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('@/components/agent/AgentInsightsPanel', () => ({ AgentInsightsPanel: () => null }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))
vi.mock('next/image', () => ({ default: () => <div data-testid="mock-image" /> }))

const FINOPS_AGENT = {
  id: 'finops-agent', plane: 'control', charter: 'Monitors spend.', owns: [], skills: [],
  dataRead: ['cost metrics'], pii: 'none', toolsAllow: ['read:costs'], toolsDeny: ['write:billing'],
  requiresHuman: ['approve savings'], tokensPerRun: 1_000, runsPerDay: 2,
}

const GOVERNANCE = {
  adrRef: 'ADR-0031', adrStatus: 'accepted', phase: 1, totalPhases: 4, phaseLabel: 'Advisory',
  enforcement: 'audit-only', policyDefault: 'deny', agentsActing: 1, chartersAvailable: true,
  agentCount: 1, agents: [FINOPS_AGENT], toolTiers: {}, decisions: [],
  decisionSummary: { built: 0, partial: 0, planned: 0, total: 0 }, compliance: [],
  auditTrail: { capture: [], pipeline: [], live: [], planned: [] },
}

function response(body: unknown) {
  return { ok: true, json: async () => body }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AIOps agent card interactions', () => {
  it('keeps profile navigation separate from nested keyboard controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/iaops/governance')) return response(GOVERNANCE)
      if (url.includes('/api/finops/ai-costs')) {
        return response({ available: true, agents: [{ agentId: 'finops-agent', costLast24hUsd: 1, costLast7dUsd: 7, budgetMonthlyUsd: 100, budgetUsedPct: 8, burnRate: 'low' }] })
      }
      return response({ anomalies: [] })
    }))
    const alertSpy = vi.fn()
    vi.stubGlobal('alert', alertSpy)
    const user = userEvent.setup()

    render(<IAOpsPage />)

    expect(screen.getByRole('heading', { name: 'What is the AI swarm?' })).toBeInTheDocument()
    expect(document.getElementById('ai-swarm')).toBeInTheDocument()
    expect(document.getElementById('ai-mesh')).toBeInTheDocument()

    const profileLink = await screen.findByRole('link', { name: /Open Fina's agent profile/i })
    expect(profileLink).toHaveAttribute('href', '/iaops/agents/finops-agent')
    expect(profileLink.closest('[role="link"]')).toBeNull()
    const linkClick = vi.fn((event: Event) => event.preventDefault())
    profileLink.addEventListener('click', linkClick)

    const summary = screen.getByText('Technical profile & guardrails')
    summary.focus()
    await user.keyboard('{Enter}')
    expect(linkClick).not.toHaveBeenCalled()

    const trigger = screen.getByRole('button', { name: 'Trigger Analysis' })
    trigger.focus()
    await user.keyboard('{Enter}')
    expect(alertSpy).toHaveBeenCalledOnce()
    expect(linkClick).not.toHaveBeenCalled()

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })
})
