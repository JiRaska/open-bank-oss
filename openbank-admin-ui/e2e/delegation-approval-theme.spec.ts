// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const approval = {
  id: 'approval-1',
  delegationId: 'delegation-1',
  operation: 'REVOKE',
  requestedReason: 'Customer withdrew the mandate.',
  state: 'PROPOSED',
  proposedBy: 'maker@example.test',
  proposedAt: '2026-09-15T00:00:00Z',
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  executedAt: null,
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/delegations/approvals/approval-1', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(approval),
  }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} delegation evidence remains clear, read-only and accessible`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('ob-admin-theme', selectedTheme), theme)
    await page.goto('/approvals/delegation/approval-1')

    await expect(page.getByRole('heading', { level: 1, name: 'Delegation approval detail' })).toBeVisible()
    await expect(page.getByText('Pending', { exact: true })).toBeVisible()
    await expect(page.getByText('Waiting for a different person')).toBeVisible()
    await expect(page.getByText('This screen is read-only.', { exact: false })).toBeVisible()
    await expect(page.getByRole('button', { name: /Approve|Reject/i })).toHaveCount(0)
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const results = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
