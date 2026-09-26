// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const approvalId = '018f4a3c-1b2d-7e00-9a11-000000000010'
const approval = {
  id: approvalId,
  delegationId: '018f4a3c-1b2d-7e00-9a11-000000000011',
  operation: 'SUSPEND',
  requestedReason: 'Review the delegated access after a customer request.',
  state: 'PROPOSED',
  proposedBy: 'first.operator',
  proposedAt: '2026-09-02T08:00:00Z',
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  executedAt: null,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: 'openbank-admin-lang', value: 'en', url: baseURL! }])
})

test('delegation approval evidence is read-only, responsive and accessible in both themes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route(`**/api/delegations/approvals/${approvalId}`, route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(approval),
  }))
  await page.goto(`/approvals/delegation/${approvalId}`)

  const main = page.locator('#main-content')
  await expect(main.getByRole('heading', { name: 'Delegation approval detail' })).toBeVisible()
  await expect(main.getByText('Pending', { exact: true })).toHaveClass(/badge-warning/)
  await expect(main.getByText('This screen is read-only.')).toBeVisible()
  await expect(main.getByText('Nothing executed — proposal pending')).toBeVisible()
  await expect(main.getByRole('button', { name: /approve|reject|execute/i })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)

  for (const dark of [false, true]) {
    if (dark) {
      await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
      await expect(page.locator('html')).toHaveClass(/dark/)
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
  await expect(main.getByText('Čeká', { exact: true })).toHaveClass(/badge-warning/)
})

test('terminal evidence distinguishes rejected from executed without implying projection delivery', async ({ page }) => {
  let state = 'REJECTED'
  await page.route(`**/api/delegations/approvals/${approvalId}`, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      ...approval,
      state,
      decidedBy: 'second.operator',
      decidedAt: '2026-09-02T09:00:00Z',
      decisionReason: 'Evidence did not support the change.',
      executedAt: state === 'EXECUTED' ? '2026-09-02T09:01:00Z' : null,
    }),
  }))
  await page.goto(`/approvals/delegation/${approvalId}`)
  const main = page.locator('#main-content')
  await expect(main.getByText('Rejected', { exact: true })).toHaveClass(/badge-danger/)
  await expect(main.getByText('Action was not executed')).toBeVisible()

  state = 'EXECUTED'
  await page.reload()
  await expect(main.getByText('Executed', { exact: true })).toHaveClass(/badge-success/)
  await expect(main.getByText('Authoritative transition recorded')).toBeVisible()
  await expect(main.getByText('This record alone does not prove delivery to product projections;')).toBeVisible()
})
