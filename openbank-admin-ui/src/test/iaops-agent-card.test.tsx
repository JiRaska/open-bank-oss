// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import userEvent from '@testing-library/user-event'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IAOpsPage from '@/app/iaops/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('@/components/agent/AgentInsightsPanel', () => ({ AgentInsightsPanel: () => null }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

const FINOPS_AGENT = {
  id: 'finops-agent', plane: 'control', charter: 'Monitors spend.', owns: [], skills: [],
  dataRead: ['cost metrics'], pii: 'none', toolsAllow: ['read:costs'], toolsDeny: ['write:billing'],
  requiresHuman: ['approve savings'], tokensPerRun: 1_000, runsPerDay: 2,
}

const GOVERNANCE = {
  adrRef: 'ADR-0031', adrStatus: 'accepted', phase: 2, totalPhases: 5, phaseLabel: 'Read-only oversight active',
  controlMaturity: { current: 4, total: 5, label: 'Four core controls are built.', achieved: ['D1', 'D2', 'D3', 'D4'], remaining: 'D5 needs independent provenance.' },
  enforcement: 'block', policyDefault: 'deny', agentsActing: 0, chartersAvailable: true,
  agentCount: 1, agents: [FINOPS_AGENT], toolTiers: {}, decisions: [],
  decisionSummary: { built: 0, partial: 0, planned: 0, total: 0 }, compliance: [],
  auditTrail: { capture: [], pipeline: [], live: [], planned: [] },
}

function response(body: unknown) {
  return { ok: true, json: async () => body }
}

const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')

afterEach(() => {
  if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView)
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  window.history.replaceState({}, '', '/')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AIOps agent card interactions', () => {
  it('does not mount the hero asset before governance data is available', async () => {
    let resolve!: (value: ReturnType<typeof response>) => void
    const pending = new Promise<ReturnType<typeof response>>(done => { resolve = done })
    vi.stubGlobal('fetch', vi.fn(() => pending))

    render(<IAOpsPage />)

    expect(screen.queryByAltText('Original team of five OpenBank AI agent robots')).toBeNull()
    await act(async () => { resolve(response(GOVERNANCE)); await pending })
    const hero = await screen.findByAltText('Original team of five OpenBank AI agent robots')
    expect(hero).toHaveAttribute('loading', 'lazy')
    expect(hero).toHaveAttribute('decoding', 'async')
    expect(hero).toHaveAttribute('width', '1200')
    expect(hero).toHaveAttribute('height', '800')
  })

  it.each([2, 3])('describes read and write policy behavior without overstating phase %i autonomy', async phase => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...GOVERNANCE, phase })))

    render(<IAOpsPage />)

    expect(await screen.findByText(/The read-only MCP path is deny-by-default/i)).toBeVisible()
    expect(screen.getByText(/a PDP outage degrades it to advisory/i)).toBeVisible()
    expect(screen.getByText(/The development PR path denies writes on a PDP outage/i)).toBeVisible()
    expect(screen.getByText(/A human decides every proposal/i)).toBeVisible()
    expect(screen.queryByText(/enforcement \(OPA block\) is not yet live/i)).not.toBeInTheDocument()
  })

  it.each([2, 3])('keeps protective control maturity separate from phase %i authority', async phase => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...GOVERNANCE, phase })))

    render(<IAOpsPage />)

    expect(await screen.findByText('Governance controls')).toBeVisible()
    expect(screen.getByText('4/5')).toBeVisible()
    expect(screen.getByText(/Controls, not authority for autonomous change/i)).toBeVisible()
    expect(screen.getByText(new RegExp(`Permitted actions are governed by phase ${phase}/5 and its independently verified operational evidence`, 'i'))).toBeVisible()
  })

  it('renders the declared control maturity instead of hard-coding four of five', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({
      ...GOVERNANCE,
      controlMaturity: { current: 3, total: 5, label: 'Three core controls are built.', achieved: ['D1', 'D2', 'D3'], remaining: 'D4 and D5 need evidence.' },
    })))

    render(<IAOpsPage />)

    expect(await screen.findByText('3 of 5 protective control families are built.')).toBeVisible()
    expect(screen.queryByText('4 of 5 protective control families are built.')).not.toBeInTheDocument()
  })

  it.each(['ai-swarm', 'ai-mesh', 'agent-roster'])('scrolls to the %s fragment after governance data loads', async fragment => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
      callback(0)
      return 1
    })
    window.history.replaceState({}, '', `/#${fragment}`)
    vi.stubGlobal('fetch', vi.fn(async () => response(GOVERNANCE)))

    render(<IAOpsPage />)

    await screen.findByRole('heading', { name: 'What is the AI swarm?' })
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' }))
  })

  it('renders unknown period costs without hiding a valid 24h cost', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/iaops/governance')) return response(GOVERNANCE)
      if (url.includes('/api/finops/ai-costs')) {
        return response({ available: true, agents: [{ agentId: 'finops-agent', costLast24hUsd: 2.5, costLast7dUsd: null, budgetMonthlyUsd: null, budgetUsedPct: null, burnRate: 'normal' }] })
      }
      return response({ anomalies: [] })
    }))

    render(<IAOpsPage />)

    expect(await screen.findByText('$2.50')).toBeVisible()
    expect(screen.getByText('No data')).toBeVisible()
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument()
  })

  it('renders unknown 24h cost without hiding a valid 7d cost', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/iaops/governance')) return response(GOVERNANCE)
      if (url.includes('/api/finops/ai-costs')) {
        return response({ available: true, agents: [{ agentId: 'finops-agent', costLast24hUsd: null, costLast7dUsd: 7, budgetMonthlyUsd: null, budgetUsedPct: null, burnRate: null }] })
      }
      return response({ anomalies: [] })
    }))

    render(<IAOpsPage />)

    expect(await screen.findByText('$7.00')).toBeVisible()
    expect(screen.getByText('No data')).toBeVisible()
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument()
  })

  it('keeps profile navigation separate from nested keyboard controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/iaops/governance')) return response(GOVERNANCE)
      if (url.includes('/api/finops/ai-costs')) {
        return response({ available: true, agents: [{ agentId: 'finops-agent', costLast24hUsd: 1, costLast7dUsd: 7, budgetMonthlyUsd: 100, budgetUsedPct: 8, burnRate: 'low' }] })
      }
      return response({ anomalies: [] })
    }))
    const user = userEvent.setup()

    render(<IAOpsPage />)

    await screen.findByRole('heading', { name: 'What is the AI swarm?' })
    expect(document.getElementById('ai-swarm')).toBeInTheDocument()
    expect(document.getElementById('ai-mesh')).toBeInTheDocument()

    const crewHeading = screen.getByRole('heading', { name: 'Meet the colleagues who never decide for you.' })
    const swarmHeading = screen.getByRole('heading', { name: 'What is the AI swarm?' })
    expect(document.body.innerHTML.indexOf(crewHeading.outerHTML)).toBeLessThan(document.body.innerHTML.indexOf(swarmHeading.outerHTML))

    const profileLink = await screen.findByRole('link', { name: /Open Fina's agent profile/i })
    expect(profileLink).toHaveAttribute('href', '/iaops/agents/finops-agent')
    expect(profileLink.closest('[role="link"]')).toBeNull()
    const linkClick = vi.fn((event: Event) => event.preventDefault())
    profileLink.addEventListener('click', linkClick)

    const summary = screen.getByText('Technical profile & guardrails')
    summary.focus()
    await user.keyboard('{Enter}')
    expect(linkClick).not.toHaveBeenCalled()

    const analysisStatus = screen.getByText('Analysis is not connected to the HITL backend yet')
    expect(analysisStatus).toBeVisible()
    expect(linkClick).not.toHaveBeenCalled()
  })
})
