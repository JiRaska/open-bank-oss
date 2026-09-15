// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme, type OperatorTheme } from './helpers/theme'

const id = '7b1f1d5e-0d2a-4a6a-8f7e-2c1b9a0d3e4f'

const detail = (state: string) => ({
  campaign: {
    id,
    name: 'Savings return journey',
    goal: 'Help eligible customers rebuild their savings habit',
    segmentRef: { name: 'actives-tenured-30d', version: 4 },
    state,
    createdBy: 'maker.operator',
    approvedBy: null,
    steps: [
      { order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, channel: 'EMAIL' },
      { order: 2, template: 'MARKETING_PRODUCT_OFFER_PUSH', delaySeconds: 86400, channel: 'PUSH' },
    ],
  },
  enrolments: [],
  sends: { items: [], total: 0, page: 0, size: 50 },
  partyNames: {},
  sendSummary: {},
  journey: [],
  engagement: [],
  incentives: null,
  experiment: null,
  contentExperiment: null,
  sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok' },
})

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('reviews activation evidence and retains a refused action for retry', async ({ page }) => {
  let decisions = 0
  let state = 'PENDING_APPROVAL'
  await page.route(`**/api/campaigns/${id}/actions`, async route => {
    decisions += 1
    expect(route.request().method()).toBe('POST')
    expect(await route.request().postDataJSON()).toEqual({ action: 'activate' })
    if (decisions === 1) {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'forbidden', error: 'Independent approver required.' }) })
      return
    }
    state = 'ACTIVE'
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'ok' }) })
  })
  await page.route(new RegExp(`/api/campaigns/${id}$`), route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(detail(state)),
  }))

  await page.goto(`/campaigns/${id}`)
  await page.getByRole('button', { name: /Approve and activate|Schválit a spustit/ }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('Savings return journey')
  await expect(dialog).toContainText('Help eligible customers rebuild their savings habit')
  await expect(dialog).toContainText('maker.operator')
  await expect(dialog).toContainText('actives-tenured-30d · v4')
  await expect(dialog).toContainText('2')

  const confirm = dialog.getByRole('button', { name: /Confirm action|Potvrdit akci/ })
  await confirm.click()
  await expect(dialog.getByRole('alert')).toContainText('Independent approver required.')
  await expect(dialog).toBeVisible()
  await confirm.click()

  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: /Enrol audience|Zařadit publikum/ })).toBeVisible()
  expect(decisions).toBe(2)
})

for (const theme of ['light', 'dark'] as const satisfies readonly OperatorTheme[]) {
  test(`inherits the ${theme} theme across campaign decision evidence`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.route(new RegExp(`/api/campaigns/${id}$`), route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(detail('PENDING_APPROVAL')),
    }))

    await page.goto(`/campaigns/${id}`)
    await page.getByRole('button', { name: /Approve and activate|Schválit a spustit/ }).click()
    const dialog = page.getByRole('alertdialog')
    const panel = page.locator('[data-campaign-action-panel]')
    await expect(dialog).toBeVisible()
    const semanticSurface = await page.evaluate(() => {
      const probe = document.createElement('div')
      probe.style.backgroundColor = 'var(--surface)'
      document.body.append(probe)
      const color = getComputedStyle(probe).backgroundColor
      probe.remove()
      return color
    })
    await expect(panel).toHaveCSS('background-color', semanticSurface)
    const results = await new AxeBuilder({ page })
      .include('[role="alertdialog"]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
