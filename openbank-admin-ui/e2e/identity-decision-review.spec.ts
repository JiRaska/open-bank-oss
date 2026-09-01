// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const caseId = '019fbe12-1111-7222-8333-444444444444'
const candidateId = '019fbe12-aaaa-7bbb-8ccc-dddddddddddd'
const identityCase = {
  id: caseId,
  trigger: 'RN_COLLISION',
  status: 'AWAITING_SECOND_APPROVAL',
  applicant: {
    givenName: 'Jana',
    familyName: 'Nováková',
    birthdate: '1986-04-12',
    birthplace: 'Brno',
    nationalities: ['CZ'],
  },
  candidatePartyIds: [candidateId],
  firstApprover: 'first.reviewer',
  firstVerdict: 'LINK_TO_EXISTING',
  firstLinkPartyId: candidateId,
  firstNotes: 'Registry number and document evidence agree.',
  firstAt: '2026-08-31T10:00:00Z',
  secondApprover: null,
  finalVerdict: null,
  decidedAt: null,
  createdAt: '2026-08-31T09:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('reviews the exact second identity vote and retains a failed decision for retry', async ({ page }) => {
  let decisions = 0
  let cases = [identityCase]
  const base = '/api/svc/pid-service/api/v1/parties/cases'
  await page.route(new RegExp(`${base}$`), route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(cases),
  }))
  await page.route(`**${base}/${caseId}/decision`, async route => {
    decisions += 1
    expect(route.request().method()).toBe('POST')
    expect(await route.request().postDataJSON()).toEqual({
      verdict: 'LINK_TO_EXISTING',
      notes: 'Second reviewer confirmed the same evidence.',
      linkPartyId: candidateId,
    })
    if (decisions === 1) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'PID temporarily unavailable.' }) })
      return
    }
    cases = []
    await route.fulfill({ contentType: 'application/json', body: '{}' })
  })

  await page.goto('/identity-cases')
  await page.getByLabel(/Decision notes|Poznámka k rozhodnutí/).fill('Second reviewer confirmed the same evidence.')
  await page.getByRole('button', { name: /Confirm & decide|Potvrdit a rozhodnout/ }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('Jana Nováková · 1986-04-12')
  await expect(dialog).toContainText(caseId)
  await expect(dialog).toContainText(`LINK_TO_EXISTING → ${candidateId}`)
  await expect(dialog).toContainText('first.reviewer → LINK_TO_EXISTING')
  await expect(dialog).toContainText('Second reviewer confirmed the same evidence.')

  const confirm = dialog.getByRole('button', { name: /Confirm vote|Potvrdit hlas/ })
  await confirm.click()
  await expect(dialog.getByRole('alert')).toContainText('PID temporarily unavailable.')
  await expect(dialog).toBeVisible()
  await confirm.click()

  await expect(dialog).toBeHidden()
  await expect(page.getByText(/No open cases|Žádné otevřené případy/)).toBeVisible()
  expect(decisions).toBe(2)
})
