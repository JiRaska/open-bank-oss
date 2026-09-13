// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const cases = [
  {
    id: '019fbe12-1111-7222-8333-444444444444',
    trigger: 'NAMESAKE_CANDIDATE',
    status: 'OPEN',
    applicant: { givenName: 'Adam', familyName: 'Novák', birthdate: '1990-01-02', birthplace: 'Praha', nationalities: ['CZ'] },
    candidatePartyIds: ['019fbe12-aaaa-7bbb-8ccc-dddddddddddd'],
    firstApprover: null,
    firstVerdict: null,
    firstLinkPartyId: null,
    firstNotes: null,
    firstAt: null,
    secondApprover: null,
    finalVerdict: null,
    decidedAt: null,
    createdAt: '2026-09-02T09:00:00Z',
  },
  {
    id: '019fbe12-2222-7333-8444-555555555555',
    trigger: 'RN_COLLISION',
    status: 'AWAITING_SECOND_APPROVAL',
    applicant: { givenName: 'Beáta', familyName: 'Svobodová', birthdate: '1984-03-04', birthplace: 'Brno', nationalities: ['CZ'] },
    candidatePartyIds: ['019fbe12-bbbb-7ccc-8ddd-eeeeeeeeeeee'],
    firstApprover: 'first.reviewer',
    firstVerdict: 'DISTINCT_NEW',
    firstLinkPartyId: null,
    firstNotes: null,
    firstAt: '2026-09-02T08:30:00Z',
    secondApprover: null,
    finalVerdict: null,
    decidedAt: null,
    createdAt: '2026-09-01T09:00:00Z',
  },
]

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('prioritizes second votes and keeps triage understandable on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/svc/pid-service/api/v1/parties/cases', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(cases),
  }))

  await page.goto('/identity-cases')

  await expect(page.getByText('2 active cases')).toBeVisible()
  await expect(page.getByText(/1 awaits an independent second vote/)).toBeVisible()
  await expect(page.getByText('Beáta Svobodová')).toBeVisible()
  await expect(page.getByText('Adam Novák')).toBeVisible()
  const secondVoteY = (await page.getByText('Beáta Svobodová').boundingBox())?.y
  const openY = (await page.getByText('Adam Novák').boundingBox())?.y
  expect(secondVoteY).toBeLessThan(openY!)
  await page.getByLabel('Review stage').selectOption('OPEN')
  await expect(page.getByText('Adam Novák')).toBeVisible()
  await expect(page.getByText('Beáta Svobodová')).toBeHidden()

  await page.getByLabel('Find a person or case').fill('nobody matches')
  await expect(page.getByRole('status')).toContainText('No cases match these filters')
  await expect(page.getByText('The active queue is unchanged.')).toBeVisible()
})
