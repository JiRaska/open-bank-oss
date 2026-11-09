// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('shows shadow mode, attributed waiver and missing monthly fee evidence', async ({ context, baseURL, page }, testInfo) => {
  await signInAsOperator(context, baseURL!)
  const readiness = {
    month: '2024-02', observedDays: 29, requiredDays: 29, eligibleForEnforcement: false,
    blockers: ['INCOMPLETE_DAILY_EVIDENCE'],
    days: Array.from({ length: 29 }, (_, index) => ({
      businessDate: `2024-02-${String(index + 1).padStart(2, '0')}`,
      blockers: index === 0 ? ['MISSING:billing'] : [],
    })),
  }
  await page.route('**/api/svc/ledger-service/api/v1/ledger/close-readiness', route =>
    route.fulfill({ json: readiness }))
  await page.route('**/api/svc/ledger-service/api/v1/ledger/accounting-days/*/participants', route =>
    route.fulfill({ json: {
      businessDate: '2024-02-01', complete: false, participantsMode: 'shadow', missing: ['interest'], refused: [],
      participants: [
        { participant: 'interest', status: 'MISSING', journalsPosted: 0, itemsProcessed: 0 },
        { participant: 'billing', status: 'WAIVED', journalsPosted: 0, itemsProcessed: 0,
          reportedBy: 'finance-operator', reason: 'accepted outage INC-1', reportedAt: '2024-02-02T06:00:00Z' },
      ],
    } }))
  await page.goto('/day-end')
  await expect(page.getByText(/Observation only|Pouze sledování/)).toBeVisible()
  await expect(page.getByText('finance-operator', { exact: true })).toBeVisible()
  await expect(page.getByText('accepted outage INC-1', { exact: true })).toBeVisible()
  await expect(page.getByText(/does not yet support activating|zatím neumožňuje schválit/)).toBeVisible()
  await page.getByText(/^(Daily evidence|Důkazy za jednotlivé dny)$/, { exact: true }).click()
  await expect(page.getByText(/Missing report: billing|Chybělo hlášení: billing/)).toBeVisible()
  await expect(page.getByText(/the day stays in CUTOFF|den zůstane v CUTOFF/)).toHaveCount(0)
  await page.getByRole('heading', { name: /Monthly close completeness evidence|Měsíční důkaz úplnosti uzávěrek/ }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('monthly-close-evidence.png'), fullPage: true })
})
