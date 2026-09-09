// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const AGGREGATE_ID = '05a02ef1-381c-40e7-b73f-d6855eead42e'
const ENTRY = {
  id: 'audit-42',
  aggregateId: AGGREGATE_ID,
  aggregateType: 'ACCOUNT',
  eventType: 'UPDATED',
  actorId: 'operator-42',
  actorType: 'USER',
  occurredAt: '2026-08-31T08:00:00Z',
  payload: { status: 'ACTIVE' },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps a failed audit retry bound to the same aggregate and recovers', async ({ page }) => {
  let available = true
  let requests = 0
  await page.route(`**/api/svc/audit-service/api/v1/audit/entries/${AGGREGATE_ID}?limit=100`, route => {
    requests += 1
    if (!available) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([ENTRY]) })
  })

  await page.goto('/audit')
  await page.getByLabel(/ID agregátu|Aggregate ID/).fill(AGGREGATE_ID)
  await page.getByRole('button', { name: /Vyhledat auditní záznam|Search audit trail/ }).click()
  await expect(page.getByText('UPDATED', { exact: true })).toBeVisible()
  await expect(page.getByText(AGGREGATE_ID, { exact: true })).toBeVisible()

  available = false
  await page.getByRole('button', { name: /Vyhledat auditní záznam|Search audit trail/ }).click()
  await expect(page.getByText(/novější události mohou chybět|newer events may be missing/)).toBeVisible()
  await expect(page.getByText('UPDATED', { exact: true })).toBeVisible()
  await expect(page.getByText(AGGREGATE_ID, { exact: true })).toBeVisible()

  available = true
  await page.getByRole('button', { name: /Vyhledat auditní záznam|Search audit trail/ }).click()
  await expect(page.getByText(/novější události mohou chybět|newer events may be missing/)).toBeHidden()
  await expect(page.getByText('UPDATED', { exact: true })).toBeVisible()
  expect(requests).toBeGreaterThanOrEqual(3)
})

test('never attributes an older audit snapshot to a different aggregate', async ({ page }) => {
  const differentAggregateId = '86d667cb-52d7-4985-8624-e8130dc28cab'
  await page.route(`**/api/svc/audit-service/api/v1/audit/entries/${AGGREGATE_ID}?limit=100`, route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([ENTRY]) }),
  )
  await page.route(`**/api/svc/audit-service/api/v1/audit/entries/${differentAggregateId}?limit=100`, route =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' }),
  )

  await page.goto('/audit')
  const input = page.getByLabel(/ID agregátu|Aggregate ID/)
  await input.fill(AGGREGATE_ID)
  await page.getByRole('button', { name: /Vyhledat auditní záznam|Search audit trail/ }).click()
  await expect(page.getByText('UPDATED', { exact: true })).toBeVisible()

  await input.fill(differentAggregateId)
  await page.getByRole('button', { name: /Vyhledat auditní záznam|Search audit trail/ }).click()
  await expect(page.getByText('UPDATED', { exact: true })).toHaveCount(0)
  await expect(page.getByText(/poslední ověřený snapshot|last verified snapshot/)).toHaveCount(0)
})
