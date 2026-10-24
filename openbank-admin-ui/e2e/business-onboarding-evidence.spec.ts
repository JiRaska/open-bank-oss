// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator, signInWithRoles } from './helpers/auth'

// The business-onboarding console (ADR-0284) is where a human confirmation turns a
// heuristic's reading of a register entry into the binding representation rule — once
// per identifier and rule wording. These tests pin its evidence contract: the queue
// count says what it counted, the filter cannot invent matches, an unauthorized
// session renders the honest refusal rather than an empty queue, and a failed load
// recovers on refresh.

const CASES = '**/api/svc/kyb-service/api/v1/kyb/cases*'
const REPRESENTATION = '**/api/svc/kyb-service/api/v1/kyb/representation/**'

const decisionFixture = {
  state: 'UNATTESTED',
  ruleText: 'Jednatel podepisuje samostatně.',
  ruleTextHash: 'hash-1',
  parserSuggestsSigners: 2,
  parserSuggestsRoles: [],
  parserMode: 'ANY_TWO',
  attestation: null,
  previous: null,
}

const casesFixture = [
  {
    id: '11111111-2222-4333-8444-555555550001',
    status: 'MANUAL_REVIEW',
    scheme: 'ICO',
    identifier: '12345678',
    requiredSignatures: null,
    requiredSignerRoles: [],
    signedCount: 0,
    reviewReason: 'Parser confidence below threshold.',
    createdAt: '2026-10-14T09:00:00Z',
    updatedAt: '2026-10-14T09:00:00Z',
    extract: {
      legalName: 'Kovárna Praha s.r.o.',
      identifier: '12345678',
      scheme: 'ICO',
      representatives: [
        { fullName: 'Jana Nováková', role: 'jednatel', body: null },
        { fullName: 'Petr Svoboda', role: 'jednatel', body: null },
      ],
      representationRule: { mode: 'ANY_ONE', requiredSigners: 1, sourceText: null, requiredRoles: [] },
    },
  },
  {
    id: '11111111-2222-4333-8444-555555550002',
    status: 'MANUAL_REVIEW',
    scheme: 'ICO',
    identifier: '87654321',
    requiredSignatures: null,
    requiredSignerRoles: [],
    signedCount: 0,
    reviewReason: null,
    createdAt: '2026-10-10T09:00:00Z',
    updatedAt: '2026-10-10T09:00:00Z',
    extract: null,
  },
]

async function stubRepresentation(page: import('@playwright/test').Page) {
  await page.route(REPRESENTATION, route => {
    if (route.request().url().endsWith('/history')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(decisionFixture) })
  })
}

test('renders the review queue with an honest count, listed representatives, and a working filter', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(CASES, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(casesFixture) }))
  await stubRepresentation(page)

  await page.goto('/business-onboarding')
  await expect(page.getByRole('heading', { name: 'Business Onboarding — Representation' })).toBeVisible()
  // The count label is a claim about what was counted — both cases, not a truncated page.
  await expect(page.getByText('2 cases awaiting review')).toBeVisible()
  // Newest first, and the extract's representatives travel with the card.
  await expect(page.getByText('Kovárna Praha s.r.o.', { exact: true })).toBeVisible()
  await expect(page.getByText(/Jana Nováková \(jednatel\)/)).toBeVisible()
  // A case without an extract names the gap instead of rendering an empty card.
  await expect(page.getByText('(unknown name)')).toBeVisible()
  // The decision renders as evidence, not just a form: the register wording and
  // the parser's suggestion (ANY_TWO / 2 signers) are shown as a suggestion only,
  // and the form pre-fills the signature count from it. Nothing is attested yet.
  await expect(page.getByText('Awaiting confirmation').first()).toBeVisible()
  await expect(page.getByText(/Parser suggestion: ANY_TWO \/ 2×/).first()).toBeVisible()
  await expect(page.getByRole('spinbutton', { name: 'Required signature count' }).first()).toHaveValue('2')
  await expect(page.getByRole('button', { name: 'Confirm the representation rule' }).first()).toBeVisible()

  // The filter can narrow to one case and admits when nothing matches.
  await page.getByPlaceholder('Company name or company ID').fill('12345678')
  await expect(page.getByText('Kovárna Praha s.r.o.', { exact: true })).toBeVisible()
  await expect(page.getByText('(unknown name)')).toBeHidden()
  await page.getByPlaceholder('Company name or company ID').fill('99999999')
  await expect(page.getByText('No case matches this filter')).toBeVisible()
  await page.getByPlaceholder('Company name or company ID').fill('')
  await expect(page.getByText('(unknown name)')).toBeVisible()
})

test('a role without the page permission gets the refusal, not a half-rendered console', async ({ page, context, baseURL }) => {
  // business-onboarding:view maps to ADMIN/OPERATOR/KYC (roles.ts) — a payments-only
  // role must see the refusal naming the required permission, and the cases endpoint
  // must not even be asked for evidence it would misuse.
  let casesRequested = false
  await signInWithRoles(context, baseURL!, ['ROLE_PAYMENTS'])
  await page.route(CASES, route => {
    casesRequested = true
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.goto('/business-onboarding')
  // The edge gate (proxy.ts route manifest, roles.ts) redirects before the page
  // renders — the refusal is a real gate, not a message on a half-rendered console.
  await expect(page).toHaveURL(/\/auth\/forbidden\?path=%2Fbusiness-onboarding$/)
  await expect(page.getByRole('heading', { name: /Tato oblast není součástí vaší role|This area is not in your role/ })).toBeVisible()
  expect(casesRequested).toBe(false)
})

test('an unauthorized session expiry banner recovers into the queue on refresh', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let denied = true
  await page.route(CASES, route => denied
    ? route.fulfill({ status: 403, contentType: 'application/json', body: '{"error":"forbidden"}' })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(casesFixture) }))
  await stubRepresentation(page)

  await page.goto('/business-onboarding')
  // 403 classifies as an authorization failure, not an outage — the operator is told
  // their session expired, not that the bank is down.
  await expect(page.getByText('Session expired')).toBeVisible()

  denied = false
  await page.getByRole('button', { name: 'Refresh the business case queue' }).click()
  await expect(page.getByText('Session expired')).toBeHidden()
  await expect(page.getByText('2 cases awaiting review')).toBeVisible()
  await expect(page.getByText('Kovárna Praha s.r.o.', { exact: true })).toBeVisible()
})
