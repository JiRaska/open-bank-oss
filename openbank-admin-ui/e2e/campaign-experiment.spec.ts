// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The experiment card is an operator decision aid, not a decorative calculation: its most
// important state is the honest "collecting data" state. Exercise the browser route so the
// authenticated app shell, page fetch and rendered decision gate agree on that fact.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CAMPAIGN_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'

const DETAIL = {
  campaign: {
    id: CAMPAIGN_ID,
    name: 'small-business-offer',
    goal: 'Measure the campaign effect without pretending early data is conclusive.',
    segmentRef: { name: 'eligible-small-businesses', version: 3 },
    state: 'ACTIVE',
    createdBy: 'campaign-maker@openbank.test',
    approvedBy: 'campaign-checker@openbank.test',
    steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0 }],
    conversionRule: 'ACCOUNT_OPENED',
    holdoutPercent: 20,
  },
  enrolments: [],
  sends: { items: [], total: 0, page: 0, size: 50 },
  partyNames: {},
  sendSummary: {},
  journey: [],
  experiment: {
    holdoutPercent: 20,
    treatment: { assigned: 99, converted: 12, conversionRate: 12 / 99 },
    holdout: { assigned: 25, converted: 2, conversionRate: 2 / 25 },
    observedLiftPercentagePoints: (12 / 99 - 2 / 25) * 100,
    decision: {
      state: 'COLLECTING_DATA',
      minimumAssignedPerCohort: 100,
      treatmentConfidenceInterval: { lower: 0.07, upper: 0.2 },
      holdoutConfidenceInterval: { lower: 0.02, upper: 0.25 },
    },
  },
  sources: {
    campaign: 'ok',
    enrolments: 'ok',
    sends: 'ok',
    sendSummary: 'ok',
    journey: 'ok',
    experiment: 'ok',
  },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('shows the control group as collecting data, never as an early winner', async ({ page }) => {
  const requests: string[] = []
  await page.route(`**/api/campaigns/${CAMPAIGN_ID}`, route => {
    requests.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`)
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(DETAIL) })
  })

  await page.goto(`/campaigns/${CAMPAIGN_ID}`)

  const experiment = page.locator('[data-experiment]')
  await expect(experiment).toBeVisible()
  await expect(experiment.getByRole('heading', { name: /Kontrolní skupina|Control group/ })).toBeVisible()
  await expect(experiment).toContainText(/99/)
  await expect(experiment).toContainText(/25/)

  const decision = experiment.locator('[data-experiment-decision]')
  await expect(decision).toContainText(/Sbíráme data|Collecting data/)
  await expect(decision).toContainText(/100/)
  await expect(decision).toContainText(/95% intervaly|95% intervals/)
  await expect(decision).not.toContainText(/automatická změna kampaně|automatic campaign change/)

  // The card must be sourced from the authenticated BFF payload, not reconstructed from sends.
  expect(requests).toEqual([`GET /api/campaigns/${CAMPAIGN_ID}`])
})
