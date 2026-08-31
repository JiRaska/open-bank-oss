// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const check = {
  id: 'check-aml-42',
  name: 'Potential Match Ltd',
  entityType: 'ORGANIZATION',
  status: 'POTENTIAL_HIT',
  overallScore: 0.87,
  checkedLists: ['EU'],
  matches: [],
  checkedAt: '2026-08-31T12:00:00Z',
}

const approval = {
  id: 'approval-sanctions-42',
  action: 'sanctions.clear',
  resourceId: check.id,
  status: 'PENDING',
  makerId: 'maker.operator',
  createdAt: '2026-08-31T12:01:00Z',
}

test.describe('sanctions maker-checker workflow', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('parks a documented disposition and lets a second operator approve a queued request', async ({ page }) => {
    let queue = [approval]
    let reviewRequests = 0
    let decisionRequests = 0

    await page.route('**/api/sanctions/checks', route =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify([check]) }),
    )
    await page.route('**/api/sanctions/lists', route =>
      route.fulfill({ contentType: 'application/json', body: '[]' }),
    )
    await page.route('**/api/sanctions/approvals', route =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify(queue) }),
    )
    await page.route('**/api/sanctions/review', async route => {
      reviewRequests += 1
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['x-approval-id']).toBeUndefined()
      expect(await route.request().postDataJSON()).toEqual({
        checkId: check.id,
        note: 'Verified against the EU source record.',
        newStatus: 'CLEAR',
      })
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'PENDING', approvalId: approval.id }),
      })
    })
    await page.route(`**/api/sanctions/approvals/${approval.id}`, async route => {
      decisionRequests += 1
      expect(route.request().method()).toBe('PATCH')
      expect(await route.request().postDataJSON()).toEqual({ approve: true })
      queue = []
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'APPROVED' }) })
    })

    await page.goto('/sanctions')
    await expect(page.getByRole('heading', { name: /Sanctions Screening|Prověření sankcí/ })).toBeVisible()
    await expect(page.getByText(check.name)).toBeVisible()

    await page.getByRole('button', { name: /Review|Posoudit/ }).click()
    await page.getByLabel(/Rationale|Odůvodnění/).fill('Verified against the EU source record.')
    await page.getByRole('button', { name: /Submit decision|Odeslat rozhodnutí/ }).click()

    await expect(page.getByText(/Awaiting a second approver|Čeká na druhého schvalovatele/)).toBeVisible()
    await expect(page.getByText(approval.id, { exact: true })).toBeVisible()
    expect(reviewRequests).toBe(1)

    const queueRow = page.getByText(approval.action).locator('..').locator('..')
    await queueRow.getByRole('button', { name: /Select|Vybrat/ }).click()
    await expect(page.getByLabel(/Approval id|ID žádosti/)).toHaveValue(approval.id)
    await page.getByRole('button', { name: /Approve|Schválit/, exact: true }).click()

    await expect(page.getByText(/Approved\. The maker can now retry the action\.|Schváleno\./)).toBeVisible()
    await expect(page.getByText(/No approvals waiting\.|Žádné čekající žádosti\./)).toBeVisible()
    expect(decisionRequests).toBe(1)
  })
})
