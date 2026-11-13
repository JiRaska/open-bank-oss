// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from './helpers/test'
import { signInAsOperator } from './helpers/auth'

// What an AML officer needs from one case, end to end in a real browser: who it is about (the
// party's name, not the UUID-shaped reference), what to assess, and a decision that carries no
// client-chosen `decidedBy` (aml-service records the authenticated principal). A pack-monitoring
// case is closed only through the four-eyes panel, whose outcome choices come from the service.

const caseId = '11111111-1111-4111-8111-111111111111'
const tmCaseId = '44444444-4444-4444-8444-444444444444'
const partyId = '22222222-2222-4222-8222-222222222222'

const onboardingCase = {
  id: caseId, partyId, accountId: null, transactionId: null, customerReference: `onboarding-${partyId}`,
  screeningType: 'CUSTOMER_ONBOARDING', riskLevel: 'HIGH', status: 'OPEN', alertCode: 'PEP_MATCH',
  alertDetail: 'Name matches a domestic PEP entry', matchedEntity: 'Jan Novák (PEP)', decisionReason: null,
  assignedAnalyst: null, decidedBy: null, screenedAt: '2026-11-12T08:00:00Z', decidedAt: null,
  createdAt: '2026-11-12T08:00:00Z', updatedAt: '2026-11-12T08:00:00Z', requiresFourEyesDisposition: false,
}
const monitoringCase = {
  ...onboardingCase, id: tmCaseId, customerReference: 'tm:velocity', screeningType: 'TRANSACTION_MONITORING',
  alertCode: 'TM_VELOCITY', alertDetail: '12 incoming transfers in 24 h', matchedEntity: null,
  status: 'UNDER_REVIEW', assignedAnalyst: 'alice', requiresFourEyesDisposition: true,
}
const vocabulary = {
  vocabularyId: 'aml-decision-outcomes', vocabularyRevision: 1, vocabularyHash: 'h',
  entries: [
    { code: 'CONFIRMED_SUSPICION', label: 'Suspicion confirmed', meaning: 'm' },
    { code: 'FALSE_POSITIVE_EXPLAINED_ACTIVITY', label: 'False positive: activity explained', meaning: 'm' },
  ],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('an officer sees who the case is about, what to assess, and records a decision', async ({ page }) => {
  const decisions: unknown[] = []
  await page.route(`**/api/svc/aml-service/api/v1/aml/cases/${caseId}/decision`, async route => {
    decisions.push(route.request().postDataJSON())
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...onboardingCase, status: 'UNDER_REVIEW', assignedAnalyst: 'operator' }) })
  })
  await page.route(`**/api/svc/aml-service/api/v1/aml/cases/${caseId}`, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(onboardingCase) }))
  await page.route('**/api/svc/aml-service/api/v1/aml/cases?**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route(`**/api/svc/party-service/api/v1/parties/${partyId}`, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: partyId, legalName: 'Jan Novák', partyType: 'INDIVIDUAL', kycStatus: 'PENDING', piiMasked: true }) }))
  await page.route('**/api/context/aml-cases/**', route => route.fulfill({ status: 404, body: '{}' }))

  await page.goto(`/aml/${caseId}`)

  await expect(page.getByRole('heading', { name: 'Jan Novák' })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('PEP_MATCH').first()).toBeVisible()
  await expect(page.getByText('Name matches a domestic PEP entry')).toBeVisible()
  await expect(page.getByText(/sanctions\/PEP match the same person|sankčním\/PEP seznamem skutečně tatáž osoba/)).toBeVisible()

  await page.getByRole('button', { name: /Take into review|Převzít do šetření/ }).click()
  await page.getByRole('button', { name: /Confirm|Potvrdit/ }).click()
  await expect(page.getByText(/Decision saved|Rozhodnutí uloženo/)).toBeVisible()
  expect(decisions).toHaveLength(1)
  expect(decisions[0]).toMatchObject({ targetStatus: 'UNDER_REVIEW' })
  expect(decisions[0]).not.toHaveProperty('decidedBy')
})

test('a pack-monitoring case is closed only through a four-eyes proposal built from the served vocabulary', async ({ page }) => {
  const proposals: unknown[] = []
  await page.route(`**/api/svc/aml-service/api/v1/aml/cases/${tmCaseId}`, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(monitoringCase) }))
  await page.route('**/api/svc/aml-service/api/v1/aml/cases?**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route('**/api/svc/aml-service/api/v1/aml/monitoring/outcomes', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(vocabulary) }))
  await page.route('**/api/svc/aml-service/api/v1/aml/monitoring/disposition-proposals?**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route('**/api/svc/aml-service/api/v1/aml/reports?**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route(`**/api/svc/aml-service/api/v1/aml/monitoring/cases/${tmCaseId}/disposition-proposals`, async route => {
    proposals.push(route.request().postDataJSON())
    await route.fulfill({ status: 201, contentType: 'application/json', body: '{}' })
  })
  await page.route(`**/api/svc/party-service/api/v1/parties/${partyId}`, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: partyId, legalName: 'Jan Novák' }) }))
  await page.route('**/api/context/aml-cases/**', route => route.fulfill({ status: 404, body: '{}' }))

  await page.goto(`/aml/${tmCaseId}`)

  await expect(page.getByRole('heading', { name: 'Jan Novák' })).toBeVisible({ timeout: 20_000 })
  // The direct decision offers only taking the case into review; closing is four-eyes.
  await expect(page.getByRole('button', { name: /^(Clear — no concern|Uzavřít bez nálezu)$/ })).toHaveCount(0)
  await expect(page.getByRole('region', { name: /Four eyes and FAU report|Čtyři oči a hlášení FAÚ/ })).toBeVisible()

  const outcome = page.getByLabel(/Assessment outcome|Výsledek posouzení/)
  await expect(outcome.locator('option')).toHaveCount(2) // placeholder + the one CLEAR-compatible served code
  await outcome.selectOption('FALSE_POSITIVE_EXPLAINED_ACTIVITY')
  await page.getByLabel(/^(Rationale|Odůvodnění návrhu) \*$/).fill('Payroll from a verified employer, payslips on file')
  await page.getByRole('button', { name: /Send for approval|Odeslat ke schválení/ }).click()
  await expect(page.getByText(/Proposal sent for second approval|Návrh odeslán ke druhému schválení/)).toBeVisible()
  expect(proposals).toEqual([{ disposition: 'CLEAR', outcomeCode: 'FALSE_POSITIVE_EXPLAINED_ACTIVITY', note: 'Payroll from a verified employer, payslips on file' }])
})
