// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const DETAIL = {
  id: 'compliance-officer',
  charter: {
    id: 'compliance-officer', plane: 'control', charter: 'Review compliance evidence',
    owns: [], skills: ['screening'], dataRead: ['audit-events'], pii: 'restricted',
    toolsAllow: ['read-evidence'], toolsDeny: ['approve-payment'],
    requiresHuman: ['release-finding'], tokensPerRun: 12000, runsPerDay: 3,
    caseCapabilities: [], schedule: null,
  },
  narrative: {
    title: 'Compliance officer', adr: 'ADR-0031', plane: 'control',
    body: '## Mission\nPrepare evidence for a human decision.',
  },
  diagnostics: [], mesh: null,
  proposals: {
    available: true, pendingCount: 1,
    items: [
      { id: 'p1', title: 'Human review needed', state: 'PROPOSED', proposedAt: '2026-09-01T10:00:00Z', decidedAt: null },
      { id: 'p2', title: 'Accepted finding', state: 'APPROVED', proposedAt: '2026-09-02T10:00:00Z', decidedAt: '2026-09-02T11:00:00Z' },
      { id: 'p3', title: 'Rejected finding', state: 'REJECTED', proposedAt: '2026-09-03T10:00:00Z', decidedAt: '2026-09-03T11:00:00Z' },
      { id: 'p4', title: 'Unrecognized finding', state: 'FUTURE_STATE', proposedAt: '2026-09-04T10:00:00Z', decidedAt: null },
    ],
  },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: 'openbank-admin-lang', value: 'en', url: baseURL! }])
})

test('agent charter keeps permissions and proposal states readable in both themes', async ({ page }) => {
  await page.route('**/api/iaops/agents/compliance-officer', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(DETAIL),
  }))
  await page.goto('/iaops/agents/compliance-officer')

  const main = page.locator('#main-content')
  await expect(main.getByText('read-evidence', { exact: true })).toHaveClass(/badge-success/)
  await expect(main.getByText('approve-payment', { exact: true })).toHaveClass(/badge-danger/)
  await expect(main.getByText('release-finding', { exact: true })).toHaveClass(/badge-warning/)
  await expect(main.getByText('Proposed', { exact: true })).toHaveClass(/badge-warning/)
  await expect(main.getByText('Approved', { exact: true })).toHaveClass(/badge-success/)
  await expect(main.getByText('Rejected', { exact: true })).toHaveClass(/badge-danger/)
  await expect(main.getByText('FUTURE_STATE', { exact: true })).toHaveClass(/badge-neutral/)

  for (const dark of [false, true]) {
    if (dark) {
      await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
      await expect(page.locator('html')).toHaveClass(/dark/)
      await expect(main.getByText('Proposed', { exact: true })).toHaveClass(/badge-warning/)
    }
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  }

  await page.getByRole('button', { name: 'Switch to Czech' }).click()
  await expect(main.getByText('Navrženo', { exact: true })).toHaveClass(/badge-warning/)
  await expect(main.getByText('Schváleno', { exact: true })).toHaveClass(/badge-success/)
  await expect(main.getByText('Zamítnuto', { exact: true })).toHaveClass(/badge-danger/)
})
