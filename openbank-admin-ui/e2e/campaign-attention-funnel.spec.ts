// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The attention funnel is a decision surface. This browser contract prevents a CSS-only regression
// from collapsing its three observed facts back into an opaque total card.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CAMPAIGN_ID = '019fb944-6c3a-7bd2-814d-7c946371f1ae'

const DETAIL = {
  campaign: {
    id: CAMPAIGN_ID,
    name: 'summer-savings-surface-test',
    goal: 'Learn which app surface earns attention.',
    segmentRef: { name: 'active-savers', version: 2 },
    state: 'ACTIVE',
    createdBy: 'campaign-maker@openbank.test',
    approvedBy: 'campaign-checker@openbank.test',
    steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER_BANNER', delaySeconds: 0 }],
  },
  enrolments: [],
  sends: { items: [], total: 0, page: 0, size: 50 },
  partyNames: {},
  sendSummary: {},
  journey: [],
  engagement: [
    { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'IMPRESSION', count: 1200 },
    { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'CLICK', count: 186 },
    { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'DISMISS', count: 31 },
  ],
  sources: {
    campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok', journey: 'ok', engagement: 'ok',
  },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('renders per-surface attention as a readable, measured funnel', async ({ page }) => {
  await page.route(`**/api/campaigns/${CAMPAIGN_ID}`, route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(DETAIL),
  }))
  await page.goto(`/campaigns/${CAMPAIGN_ID}`)

  const funnel = page.getByTestId('campaign-attention-funnel')
  await expect(funnel).toBeVisible()
  await expect(funnel.getByRole('heading', { name: /Co lidé skutečně udělali|What people actually did/ })).toBeVisible()
  await expect(funnel).toContainText(/Home banner/)
  await expect(funnel).toContainText(/1,200/)
  await expect(funnel).toContainText(/15.5 %/)
  await expect(funnel).toContainText(/neither people nor business conversions|Nejsou to lidé ani obchodní konverze/)

  // A visual hierarchy matters here: three observations remain three independent, legible metric
  // tiles instead of wrapping into an unreadable line on a normal laptop viewport.
  const metrics = funnel.locator('.campaign-attention-metrics')
  expect(await metrics.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length)).toBe(3)
  const card = funnel.locator('.campaign-attention-card')
  expect(await card.evaluate(el => getComputedStyle(el).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)')
  expect(await funnel.evaluate(el => getComputedStyle(el).backgroundImage)).toContain('gradient')
})
