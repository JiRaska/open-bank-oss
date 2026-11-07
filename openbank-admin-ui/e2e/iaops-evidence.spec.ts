// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// E2E evidence for the IAOps landing page (/iaops): the governance payload render, the
// degradation contract (finops panels are optional reads that must never take the page
// down), the governance-level failure state, and the HolmesGPT RCA form. Complements
// flaky-test-hunter-evidence.spec.ts (#12874): together they give the iaops route group
// coverage of both its surfaces.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const GOVERNANCE_URL = '**/api/iaops/governance'
const AI_COSTS_URL = '**/api/finops/ai-costs'
const ANOMALIES_URL = '**/api/finops/anomalies'
const RCA_URL = '**/api/iaops/rca'

const GOV = {
  adrRef: 'ADR-0031',
  adrStatus: 'accepted',
  phase: 2,
  totalPhases: 4,
  phaseLabel: 'Assisted',
  enforcement: 'advisory',
  policyDefault: 'deny',
  agentsActing: 3,
  phaseRoadmap: [
    { number: 1, status: 'complete', title: 'Read-only oversight', outcome: 'agents read, humans act' },
    { number: 2, status: 'active', title: 'Assisted operation', outcome: 'agents propose, humans decide' },
  ],
  controlMaturity: {
    current: 3,
    total: 5,
    label: 'Assisted.',
    achieved: ['human approval queue', 'audit capture', 'read-only tools'],
    remaining: 'independent verification of operational evidence',
  },
  chartersAvailable: true,
  agentCount: 2,
  agents: [
    {
      id: 'sentinel', plane: 'control', charter: 'sentinel.md', owns: ['alert triage'], skills: ['prometheus'],
      dataRead: ['metrics'], pii: 'none', toolsAllow: ['query'], toolsDeny: ['write'],
      requiresHuman: ['every state change'], tokensPerRun: 1200, runsPerDay: 50,
    },
    {
      id: 'sre-helper', plane: 'development', charter: 'sre-helper.md', owns: ['release notes'], skills: ['git'],
      dataRead: ['prs'], pii: 'none', toolsAllow: ['read'], toolsDeny: ['merge'],
      requiresHuman: ['merge to main'], tokensPerRun: null, runsPerDay: null,
    },
  ],
  toolTiers: { query: ['prometheus', 'k8s'], act: [] },
  decisions: [
    { id: 'd1', title: 'Read-only posture', status: 'built', detail: 'agents cannot write' },
    { id: 'd2', title: 'Human approval queue', status: 'built', detail: 'PRs need a human' },
  ],
  decisionSummary: { built: 2, partial: 0, planned: 1, total: 3 },
  compliance: [
    { framework: 'EU AI Act', requirement: 'human oversight', control: 'approval queue', status: 'built' },
  ],
  auditTrail: {
    capture: ['prompt', 'output'],
    pipeline: ['collect', 'seal', 'store'],
    live: ['query audit'],
    planned: ['cross-check replay'],
  },
}

async function stubFinops(page: import('@playwright/test').Page) {
  await page.route(AI_COSTS_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ available: true, agents: [], coverage: null }),
  }))
  await page.route(ANOMALIES_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ anomalies: [] }),
  }))
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(GOVERNANCE_URL, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(GOV),
  }))
  await stubFinops(page)
})

test('governance payload renders headline, roadmap, roster and audit trail', async ({ page }) => {
  await page.goto('/iaops')

  await expect(page.getByRole('heading', { name: 'Agent Control Room' })).toBeVisible()
  // Headline stats come from the same payload the sections expand.
  await expect(page.getByText('Governance controls').first()).toBeVisible()
  await expect(page.getByText('3/5').first()).toBeVisible()
  await expect(page.getByText('Assisted operation').first()).toBeVisible()
  // Agent roster lists both chartered agents with their planes.
  await expect(page.getByText('sentinel').first()).toBeVisible()
  await expect(page.getByText('sre-helper').first()).toBeVisible()
  // Audit trail renders capture chips and the pipeline chain.
  await expect(page.getByText('prompt', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('seal', { exact: true }).first()).toBeVisible()
})

test('failed finops reads degrade silently and never take the governance page down', async ({ page }) => {
  await page.unroute(AI_COSTS_URL)
  await page.route(AI_COSTS_URL, route => route.fulfill({ status: 500, body: 'boom' }))
  await page.unroute(ANOMALIES_URL)
  await page.route(ANOMALIES_URL, route => route.fulfill({ status: 500, body: 'boom' }))

  await page.goto('/iaops')

  // The page is one governance read plus two OPTIONAL reads; the optionals failing must
  // leave every governance section intact.
  await expect(page.getByRole('heading', { name: 'Agent Control Room' })).toBeVisible()
  await expect(page.getByText('sentinel').first()).toBeVisible()
  await expect(page.getByText(/Failed to load|not responding/)).toHaveCount(0)
})

test('a failed governance read replaces the whole page with the unavailable state', async ({ page }) => {
  await page.unroute(GOVERNANCE_URL)
  await page.route(GOVERNANCE_URL, route => route.fulfill({ status: 500, body: 'boom' }))

  await page.goto('/iaops')

  await expect(page.getByText(/Failed to load: AI governance|is not responding|is not deployed/)).toBeVisible()
  // Half-rendered governance sections would imply controls that were never validated.
  await expect(page.getByText('sentinel')).toHaveCount(0)
  await expect(page.getByText('Governance controls')).toHaveCount(0)
})

test('RCA form posts the alert description and renders the investigation result', async ({ page }) => {
  let postedAsk: string | null = null
  await page.route(RCA_URL, async route => {
    expect(route.request().method()).toBe('POST')
    postedAsk = (route.request().postDataJSON() as { ask: string }).ask
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ rca: 'probable cause: memory limit below the JVM heap request' }),
    })
  })

  await page.goto('/iaops')
  await page.getByLabel('Alert description for RCA').fill('PodCrashLooping in payments, OOMKilled 8x in 15 minutes')
  await page.getByRole('button', { name: /Investigate/ }).click()

  await expect.poll(() => postedAsk).toBe('PodCrashLooping in payments, OOMKilled 8x in 15 minutes')
  await expect(page.getByText('probable cause: memory limit below the JVM heap request')).toBeVisible()
})

test('an RCA failure renders the error, never a stale result', async ({ page }) => {
  await page.route(RCA_URL, route => route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'holmes gateway timeout' }),
  }))

  await page.goto('/iaops')
  await page.getByLabel('Alert description for RCA').fill('payments pods restarting')
  await page.getByRole('button', { name: /Investigate/ }).click()

  await expect(page.getByText('holmes gateway timeout')).toBeVisible()
  await expect(page.getByText('probable cause:', { exact: false })).toHaveCount(0)
})
