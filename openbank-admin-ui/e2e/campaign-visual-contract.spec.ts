// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// These tests intentionally assert the visual contracts, rather than a platform-specific bitmap.
// A marketer's core campaign decisions must stay scannable on a normal desktop viewport regardless
// of the operating system that renders the browser: authoring canvas + app preview in Studio, then
// outcome + attention evidence in the detail.

import { expect, test, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CAMPAIGN_ID = '019fb94e-d0da-7f5d-b0f0-81efc5ddb808'

const CONTENT_CATALOGUE = [
  { template: 'MARKETING_PRODUCT_OFFER', channel: 'EMAIL', variables: ['offerTitle', 'offerText', 'ctaText'] },
  { template: 'MARKETING_PRODUCT_OFFER_PUSH', channel: 'PUSH', variables: ['offerTitle'] },
  { template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'HOME_BANNER' },
]

async function mockComposerCatalogues(page: Page) {
  await page.route(/\/api\/audiences$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ name: 'active-savers', version: 2, rules: ['party status is ACTIVE'], state: 'APPROVED' }] }),
  }))
  await page.route(/\/api\/segments$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ name: 'active-savers', version: 2, rules: ['party status is ACTIVE'] }] }),
  }))
  await page.route(/\/api\/campaigns\/cadences$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }] }),
  }))
  await page.route(/\/api\/campaigns\/triggers$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ trigger: 'ACCOUNT_OPENED', humanForm: 'when an account is opened' }] }),
  }))
  await page.route(/\/api\/campaigns\/templates$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: CONTENT_CATALOGUE }),
  }))
  await page.route(/\/api\/campaigns\/guardrails$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      state: 'ok',
      guardrails: { maxSendsPerParty: 2, sendWindowHours: 168, quietHoursStart: 21, quietHoursEnd: 8, timeZone: 'Europe/Prague' },
    }),
  }))
  await page.route(/\/api\/segments\/active-savers\/2\/preview$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', size: 12480 }),
  }))
  await page.route(/\/api\/audiences\/active-savers\/2\/preview$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', size: 12480 }),
  }))
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps app-first authoring decisions visibly paired on a desktop canvas', async ({ page }) => {
  await mockComposerCatalogues(page)
  await page.setViewportSize({ width: 1440, height: 1050 })
  await page.goto('/campaigns/new')

  await page.locator('#c-name').fill('Savings return journey')
  await page.locator('[data-segment="active-savers@2"]').click()
  await page.locator('[data-channel-pick="BANNER"]').click()

  const workbench = page.locator('.campaign-journey-workbench')
  const preview = page.getByTestId('campaign-experience-preview')
  const readiness = page.getByTestId('campaign-launch-readiness')

  await expect(workbench).toBeVisible()
  await expect(preview).toBeVisible()
  await expect(readiness).toBeVisible()
  await expect(page.getByTestId('campaign-surface-map')).toContainText(/Banner v aplikaci|In-app banner/)
  await expect(preview).toContainText(/Domovská obrazovka|Home/)
  await expect(readiness).toContainText(/12[ ,]480/)

  // The entry explanation must sit below its title rather than consuming the title's width. This
  // keeps a marketer's first decision legible at normal desktop widths and lets a narrow card
  // turn its entry choices into readable rows rather than three crushed form controls.
  const entry = page.locator('.campaign-entry-card')
  await expect(entry.getByRole('heading', { name: /When the journey starts/ })).toBeVisible()
  expect((await entry.getByRole('heading', { name: /When the journey starts/ }).boundingBox())?.width).toBeGreaterThan(250)
  await page.setViewportSize({ width: 1280, height: 1050 })
  expect(await entry.locator('.campaign-entry-options').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length)).toBeGreaterThanOrEqual(1)
  expect(await entry.evaluate(el => el.scrollWidth)).toBeLessThanOrEqual(await entry.evaluate(el => el.clientWidth))

  // The companion panels are an intentional two-up composition on desktop. If a CSS regression
  // collapses either panel to a sliver or a stacked mobile layout, a marketer loses the immediate
  // "what will people see / is it safe to launch" feedback loop.
  const companion = page.locator('.campaign-studio-companion-grid')
  const [companionColumns, previewBox, readinessBox] = await Promise.all([
    companion.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length),
    preview.boundingBox(),
    readiness.boundingBox(),
  ])
  expect(companionColumns).toBe(2)
  expect(previewBox?.width).toBeGreaterThan(380)
  expect(readinessBox?.width).toBeGreaterThan(380)
  expect(Math.abs((previewBox?.y ?? 0) - (readinessBox?.y ?? 0))).toBeLessThan(8)
  expect(await workbench.evaluate(el => getComputedStyle(el).backgroundImage)).toContain('gradient')

  // The app-first feedback loop must remain usable below every responsive breakpoint: the two
  // panels intentionally stack, but the real customer surface neither disappears nor overflows.
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('campaign-surface-map')).toBeVisible()
  const [mobileCompanionColumns, mobilePreview, mobileReadiness, documentWidth] = await Promise.all([
    companion.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length),
    preview.boundingBox(),
    readiness.boundingBox(),
    page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth })),
  ])
  expect(mobileCompanionColumns).toBe(1)
  expect(mobileReadiness?.y).toBeGreaterThan((mobilePreview?.y ?? 0) + 20)
  expect(documentWidth.scroll).toBeLessThanOrEqual(documentWidth.client)
})

test('keeps campaign outcome and app attention as distinct, readable decision surfaces', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1050 })
  await page.route(`**/api/campaigns/${CAMPAIGN_ID}`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      campaign: {
        id: CAMPAIGN_ID,
        name: 'Savings banner evidence',
        goal: 'Learn whether the app surface earns attention.',
        segmentRef: { name: 'active-savers', version: 2 },
        state: 'ACTIVE',
        createdBy: 'campaign-maker@openbank.test',
        approvedBy: 'campaign-checker@openbank.test',
        conversionRule: 'ACCOUNT_OPENED',
        steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', delaySeconds: 0 }],
      },
      enrolments: Array.from({ length: 24 }, (_, index) => ({ id: `enrolment-${index}`, partyId: `party-${index}`, state: 'ACTIVE', currentStep: 1 })),
      sends: { items: [], total: 0, page: 0, size: 50 },
      partyNames: {},
      sendSummary: { SENT: 18, SUPPRESSED_CAP: 3, CONVERTED: 4 },
      journey: [],
      engagement: [
        { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'IMPRESSION', count: 1200 },
        { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'CLICK', count: 186 },
        { stepOrder: 1, channel: 'BANNER', surface: 'HOME_BANNER', type: 'DISMISS', count: 31 },
      ],
      sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok', journey: 'ok', engagement: 'ok' },
    }),
  }))

  await page.goto(`/campaigns/${CAMPAIGN_ID}`)

  const outcome = page.getByTestId('campaign-outcome-brief')
  const attention = page.getByTestId('campaign-attention-funnel')
  await expect(outcome).toBeVisible()
  await expect(attention).toBeVisible()
  await expect(outcome).toContainText(/18/)
  await expect(outcome.locator('[data-conversion="measured"] > strong')).toHaveText('4')
  await expect(attention).toContainText(/1,200/)
  await expect(attention).toContainText(/186/)

  // Outcome and engagement are deliberately separate cards: event activity must never visually
  // impersonate a banking conversion. Each metric group stays a real desktop grid, not a wrapped
  // text line, which is the most common regression in dense operator screens.
  const [outcomeMetrics, attentionMetrics] = await Promise.all([
    outcome.locator('.campaign-outcome-metrics').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length),
    attention.locator('.campaign-attention-metrics').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length),
  ])
  expect(outcomeMetrics).toBe(4)
  expect(attentionMetrics).toBe(3)
  expect(await outcome.evaluate(el => getComputedStyle(el).backgroundImage)).toContain('gradient')
  expect(await attention.evaluate(el => getComputedStyle(el).backgroundImage)).toContain('gradient')

  // At phone width the outcome changes to a two-column metric grid and the action card stacks
  // below it. The evidence remains separate and readable rather than becoming an off-screen row.
  await page.setViewportSize({ width: 390, height: 844 })
  const [mobileOutcomeMetrics, mainOutcome, outcomeAction, documentWidth] = await Promise.all([
    outcome.locator('.campaign-outcome-metrics').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').filter(Boolean).length),
    outcome.locator('.campaign-outcome-main').boundingBox(),
    outcome.getByTestId('campaign-outcome-next-action').boundingBox(),
    page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth })),
  ])
  expect(mobileOutcomeMetrics).toBe(2)
  expect(outcomeAction?.y).toBeGreaterThan((mainOutcome?.y ?? 0) + 20)
  expect(documentWidth.scroll).toBeLessThanOrEqual(documentWidth.client)
})
