// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// E2E evidence for the merchants catalogue page (/merchants), beyond the pagination
// recovery already covered by merchant-pagination-recovery.spec.ts:
//
// - the failure-isolation contract: a dead unmatched worklist or logo-sources read
//   must never blank the catalogue rows an operator is editing
// - the unmatched-descriptor → catalogue-entry flow (draft prefill, Save gating,
//   PUT shape, refusal message surfacing)
// - the catalogue-level failure state hides the worklist entirely

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const SVC = '**/api/svc/transaction-service/api/v1/merchants'
const LIST = `${SVC}?**`
const UNMATCHED = `${SVC}/unmatched**`
const LOGO_SOURCES = `${SVC}/logo-sources`

const row = (descriptorKey: string, cleanName: string) => ({
  descriptorKey,
  cleanName,
  logoUrl: null,
  logoContentHash: null,
  category: null,
  lat: null,
  lon: null,
  city: null,
  country: null,
  updatedAt: '2026-10-10T08:00:00Z',
})

async function stubCatalogue(page: import('@playwright/test').Page, rows: unknown[], total?: number) {
  await page.route(LIST, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ data: rows, total: total ?? rows.length }),
  }))
  await page.route(UNMATCHED, route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route(LOGO_SOURCES, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ enabled: false, allowedHosts: [] }),
  }))
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('a failed worklist or logo-sources read must not blank the catalogue', async ({ page }) => {
  await stubCatalogue(page, [row('COFFEECO', 'Coffee Company')])
  await page.unroute(UNMATCHED)
  await page.route(UNMATCHED, route => route.fulfill({ status: 500, body: 'boom' }))
  await page.unroute(LOGO_SOURCES)
  await page.route(LOGO_SOURCES, route => route.fulfill({ status: 500, body: 'boom' }))

  await page.goto('/merchants')

  // The row the operator is editing survives both dead reads.
  await expect(page.getByText('Coffee Company')).toBeVisible()
  await expect(page.getByText('Nothing unmatched in the recent window.')).toBeVisible()
  // A failed logo-sources read is treated as "off", so no logo-fetch action is offered.
  await expect(page.getByText('Fetch a logo')).toHaveCount(0)
})

test('unmatched descriptor seeds a catalogue draft and saves as a full PUT body', async ({ page }) => {
  await stubCatalogue(page, [])
  await page.unroute(UNMATCHED)
  await page.route(UNMATCHED, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{ descriptorKey: 'AMZN MKTP US*Z25', occurrences: 42 }]),
  }))

  let putBody: Record<string, unknown> | null = null
  await page.route(`${SVC}/AMZN%20MKTP%20US*Z25`, route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))

  await page.goto('/merchants')

  const chip = page.getByRole('button', { name: /Add AMZN MKTP US\*Z25/ })
  await expect(chip).toContainText('×42')
  await chip.click()

  // Draft opens with the descriptor prefilled; Save stays disabled without a trading name.
  await expect(page.getByText('Catalogue entry')).toBeVisible()
  await expect(page.getByLabel('Descriptor')).toHaveValue('AMZN MKTP US*Z25')
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled()

  await page.route(`${SVC}/AMZN%20MKTP%20US*Z25`, async route => {
    expect(route.request().method()).toBe('PUT')
    putBody = route.request().postDataJSON() as Record<string, unknown>
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.getByLabel('Trading name').fill('Amazon Marketplace')
  await page.getByRole('button', { name: 'Save', exact: true }).click()

  await expect.poll(() => putBody).not.toBeNull()
  // Blank optional fields serialise as null — never as empty strings, never half a coordinate pair.
  expect(putBody).toEqual({
    cleanName: 'Amazon Marketplace',
    category: null,
    city: null,
    country: null,
    lat: null,
    lon: null,
  })
  // A successful save closes the draft.
  await expect(page.getByText('Catalogue entry')).toHaveCount(0)
})

test('a save refusal surfaces the service message in the action alert', async ({ page }) => {
  await stubCatalogue(page, [])
  await page.unroute(UNMATCHED)
  await page.route(UNMATCHED, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{ descriptorKey: 'STARBUCKS#PRAGUE', occurrences: 7 }]),
  }))
  await page.route(`${SVC}/STARBUCKS%23PRAGUE`, route => route.fulfill({
    status: 409,
    contentType: 'application/json',
    body: JSON.stringify({ message: 'descriptor already normalised as STARBUCKS PRAGUE' }),
  }))

  await page.goto('/merchants')
  await page.getByRole('button', { name: /Add STARBUCKS#PRAGUE/ }).click()
  await page.getByLabel('Trading name').fill('Starbucks Prague')
  await page.getByRole('button', { name: 'Save', exact: true }).click()

  await expect(page.getByRole('alert').filter({ hasText: 'descriptor already normalised' })).toBeVisible()
  // The draft stays open so the operator can correct the entry, not retype it.
  await expect(page.getByText('Catalogue entry')).toBeVisible()
})

test('a failed catalogue load renders the unavailable state and hides the worklist', async ({ page }) => {
  await page.route(LIST, route => route.fulfill({ status: 502, body: 'bad gateway' }))
  await page.route(UNMATCHED, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([{ descriptorKey: 'AMZN MKTP US*Z25', occurrences: 42 }]),
  }))
  await page.route(LOGO_SOURCES, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ enabled: false, allowedHosts: [] }),
  }))

  await page.goto('/merchants')

  await expect(page.getByText(/Failed to load|not responding|is not deployed/)).toBeVisible()
  // Editing surfaces stay hidden: with the catalogue unreadable there is nothing to attach a
  // worklist entry or logo action to.
  await expect(page.getByText('Unmatched descriptors')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Add AMZN MKTP US\*Z25/ })).toHaveCount(0)
})
