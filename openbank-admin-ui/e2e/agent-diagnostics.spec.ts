// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const diagnostics = [
  { key: 'data', value: 3, fleetMax: 6, percent: 50 },
  { key: 'tools', value: 4, fleetMax: 8, percent: 50 },
  { key: 'guardrails', value: 5, fleetMax: 5, percent: 100 },
  { key: 'domain', value: 2, fleetMax: 4, percent: 50 },
  { key: 'tokens', value: 20_000, fleetMax: 40_000, percent: 50 },
  { key: 'cadence', value: 4, fleetMax: 8, percent: 50 },
]

const detail = {
  id: 'finops-agent',
  charter: {
    id: 'finops-agent', plane: 'control', charter: 'Cost stewardship', owns: ['finops'], skills: ['cost-analysis'],
    dataRead: ['billing'], pii: 'none', toolsAllow: ['cost.read'], toolsDeny: ['payment.write'],
    requiresHuman: ['every: proposal'], tokensPerRun: 20_000, runsPerDay: 4,
    caseCapabilities: ['case.open'], schedule: null,
  },
  narrative: null,
  diagnostics,
  mesh: {
    coordinatorId: 'case-coordinator', selectedCapabilities: ['case.open'],
    charteredParticipantIds: ['rca-investigator'], declaredCaseClasses: ['cost-anomaly'], totalAgents: 8,
    synthesisEnabled: true, humanGateEnabled: true, state: 'chartered',
  },
  proposals: {
    available: true,
    pendingCount: 1,
    items: [
      { id: 'proposal-1', title: 'Reduce idle capacity', state: 'PROPOSED', proposedAt: '2026-09-14T08:00:00Z', decidedAt: null },
      { id: 'proposal-2', title: 'Right-size reporting', state: 'APPROVED', proposedAt: '2026-09-07T08:00:00Z', decidedAt: '2026-09-07T09:00:00Z' },
      { id: 'proposal-3', title: 'Disable payment control', state: 'REJECTED', proposedAt: '2026-09-01T08:00:00Z', decidedAt: '2026-09-01T08:30:00Z' },
    ],
  },
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/iaops/agents/finops-agent', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(detail) }),
  )
})

test.describe('agent diagnostic education', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme explains limits and human control accessibly`, async ({ page }) => {
      await page.addInitScript(selectedTheme => {
        window.localStorage.setItem('openbank-theme', selectedTheme)
      }, theme)
      await page.goto('/iaops/agents/finops-agent')

      const analysis = page.locator('section[aria-labelledby="agent-body-analysis-title"]')
      const mesh = page.locator('section[aria-labelledby="agent-mesh-title"]')
      await expect(analysis.getByRole('heading', { name: /Agent body analysis|Analýza těla agenta/i })).toBeVisible()
      await expect(analysis.getByText(/Not an intelligence or quality score|Není to skóre inteligence ani kvality/i)).toBeVisible()
      await expect(analysis.getByRole('progressbar')).toHaveCount(6)
      await expect(mesh.getByText(/Actual runtime admission|Skutečné runtime přijetí/i)).toBeVisible()
      await expect(mesh.getByText(/Human decides|Člověk rozhodne/i)).toBeVisible()
      await expect(page.getByText(/1 pending approval|1 čeká na schválení/i)).toBeVisible()
      await expect(page.getByText('PROPOSED', { exact: true })).toBeVisible()
      await expect(page.getByText('APPROVED', { exact: true })).toBeVisible()
      await expect(page.getByText('REJECTED', { exact: true })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }

  test('mobile view preserves the five-stage governed flow', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/iaops/agents/finops-agent')

    const mesh = page.locator('section[aria-labelledby="agent-mesh-title"]')
    await expect(mesh.getByRole('listitem')).toHaveCount(5)
    const connector = await mesh.getByRole('listitem').first().evaluate(element =>
      getComputedStyle(element, '::after').content,
    )
    expect(connector).toContain('↓')
  })
})
