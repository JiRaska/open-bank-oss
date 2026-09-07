// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CAMPAIGN_ID = '019fb93a-0d54-7f05-aad3-9e8c7c9bc110'
const CONTENT_CATALOGUE = [
  { template: 'MARKETING_PRODUCT_OFFER', channel: 'EMAIL', variables: ['offerTitle', 'offerText', 'ctaText'] },
  { template: 'MARKETING_PRODUCT_OFFER_PUSH', channel: 'PUSH', variables: ['offerTitle'] },
  { template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'HOME_BANNER' },
  { template: 'MARKETING_PRODUCT_OFFER_CAROUSEL', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'HOME_CAROUSEL' },
  { template: 'MARKETING_PRODUCT_OFFER_PRODUCT_FEED', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'PRODUCT_FEED' },
  { template: 'MARKETING_PRODUCT_OFFER_REWARDS_HUB', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'REWARDS_HUB' },
]

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('authors a measured A/B content experiment and renders its conservative result', async ({ page }) => {
  let createBody: Record<string, unknown> | undefined
  await page.route(/\/api\/segments$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'] }] }),
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
  await page.route(/\/api\/campaigns$/, route => {
    if (route.request().method() !== 'POST') return route.fallback()
    createBody = route.request().postDataJSON() as Record<string, unknown>
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ state: 'ok', campaign: { id: CAMPAIGN_ID } }) })
  })
  await page.route(new RegExp(`/api/campaigns/${CAMPAIGN_ID}$`), route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      campaign: {
        id: CAMPAIGN_ID,
        name: 'Headline comparison',
        goal: 'Open more accounts',
        segmentRef: { name: 'actives', version: 1 },
        state: 'ACTIVE',
        createdBy: 'operator',
        approvedBy: 'checker',
        conversionRule: 'ACCOUNT_OPENED',
        steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, variantBVariables: { offerTitle: 'B' } }],
      },
      enrolments: [],
      sends: { items: [], total: 0, page: 0, size: 50 },
      partyNames: {},
      sendSummary: {},
      journey: [],
      contentExperiment: {
        a: { assigned: 120, converted: 10, conversionRate: 10 / 120 },
        b: { assigned: 120, converted: 30, conversionRate: 0.25 },
        observedLiftPercentagePoints: 16.666667,
        decision: {
          state: 'B_OUTPERFORMS_A',
          minimumAssignedPerVariant: 100,
          aConfidenceInterval: { lower: 0.04, upper: 0.16 },
          bConfidenceInterval: { lower: 0.18, upper: 0.34 },
        },
      },
      sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok', journey: 'ok', contentExperiment: 'ok' },
    }),
  }))

  await page.goto('/campaigns/new')
  await page.locator('#c-name').fill('Headline comparison')
  await page.locator('#c-goal').fill('Open more accounts')
  // Mandatory in the studio since ADR-0269 (#8773): the maker states whether the campaign
  // sells credit, so the journey through the form has to state it too.
  await page.locator('#c-product-kind').selectOption('NONE')
  await page.locator('[data-segment="actives@1"]').click()
  // The studio is app-first by default. This scenario deliberately authors the richer e-mail
  // template because both experiment arms exercise its complete variable contract.
  await page.locator('[data-channel-pick="EMAIL"]').click()
  await page.locator('#var-0-offerTitle').fill('Save more with A')
  await page.locator('#var-0-offerText').fill('A copy')
  await page.locator('#var-0-ctaText').fill('Open')
  await page.locator('[data-conversion-pick="ACCOUNT_OPENED"]').click()
  await page.locator('#c-content-experiment').check()
  await page.locator('#var-b-0-offerTitle').fill('Save more with B')
  await page.locator('#var-b-0-offerText').fill('B copy')
  await page.locator('#var-b-0-ctaText').fill('Try')
  await page.getByRole('button', { name: /Založit koncept|Create draft/ }).click()

  await expect.poll(() => createBody).toMatchObject({
    conversionRule: 'ACCOUNT_OPENED',
    steps: [{ variables: { offerTitle: 'Save more with A' }, variantBVariables: { offerTitle: 'Save more with B' } }],
  })
  await expect(page).toHaveURL(`/campaigns/${CAMPAIGN_ID}`)
  await expect(page.locator('[data-content-experiment-decision]')).toContainText(/variant B|varianty B/i)
  await expect(page.locator('[data-content-experiment-decision]')).toContainText(/not deploy|sám nenasazuje/i)
})
