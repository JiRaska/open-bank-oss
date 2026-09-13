// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CAMPAIGN_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'
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

test('authors a recurring campaign from the service cadence catalogue', async ({ page }) => {
  let createBody: Record<string, unknown> | undefined
  await page.route('**/api/segments', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'] }] }),
  }))
  await page.route('**/api/campaigns/cadences', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      state: 'ok',
      items: [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }],
    }),
  }))
  await page.route('**/api/campaigns/triggers', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{ trigger: 'ACCOUNT_OPENED', humanForm: 'when an account is opened' }] }),
  }))
  await page.route('**/api/campaigns/templates', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: CONTENT_CATALOGUE }),
  }))
  await page.route('**/api/campaigns', route => {
    if (route.request().method() !== 'POST') return route.fallback()
    createBody = route.request().postDataJSON() as Record<string, unknown>
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ state: 'ok', campaign: { id: CAMPAIGN_ID } }) })
  })
  await page.route(`**/api/campaigns/${CAMPAIGN_ID}`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      campaign: { id: CAMPAIGN_ID, name: 'Recurring welcome', goal: 'Engage new customers', segmentRef: { name: 'actives', version: 1 }, state: 'DRAFT', createdBy: 'operator', approvedBy: null, steps: [] },
      enrolments: [], sends: { items: [], total: 0, page: 0, size: 50 }, partyNames: {}, sendSummary: {}, journey: [], sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok', journey: 'ok', experiment: 'not_configured' },
    }),
  }))

  await page.goto('/campaigns/new')
  await page.locator('#c-name').fill('Recurring welcome')
  await page.locator('#c-goal').fill('Engage new customers')
  // Mandatory in the studio since ADR-0269 (#8773): the maker states whether the campaign
  // sells credit, so the journey through the form has to state it too.
  await page.locator('#c-product-kind').selectOption('NONE')
  await page.locator('[data-segment="actives@1"]').click()
  // Scheduling is channel-agnostic; keep this regression on e-mail so it still proves the full
  // template payload while the product's default remains app-first PUSH.
  await page.locator('[data-channel-pick="EMAIL"]').click()
  await page.locator('#var-0-offerTitle').fill('Welcome')
  await page.locator('#var-0-offerText').fill('Thanks for joining.')
  await page.locator('#var-0-ctaText').fill('Explore')
  await page.locator('[data-entry-pick="SCHEDULE"]').click()
  await expect(page.locator('[data-cadence]')).toHaveValue('DAILY_MORNING')
  await page.getByRole('button', { name: /Založit koncept|Create draft/ }).click()

  await expect.poll(() => createBody).toMatchObject({ schedule: { cadence: 'DAILY_MORNING' } })
  expect(createBody).not.toHaveProperty('trigger')
  await expect(page).toHaveURL(`/campaigns/${CAMPAIGN_ID}`)
})
