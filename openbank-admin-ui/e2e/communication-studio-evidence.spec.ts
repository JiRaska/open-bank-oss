// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// Communication Studio phase 1 (ADR-0285 D7) is a read-only MIRROR of the prompt
// registry, not an editor — the phase-1 contract is that it never reads as a
// capability that silently does nothing: the locked core says who owns it, the
// style/playbook rows say which phase lands them, and a missing registry is an
// honest absence, not an empty table that looks healthy.

const PERSONAS = '**/api/communication/personas'
const PERSONA_DETAIL = '**/api/communication/personas/**'

const listFixture = {
  available: true,
  schemaVersion: 1,
  relatedAdrs: ['ADR-0285', 'ADR-0148'],
  personas: [
    {
      id: 'branch-assistant',
      status: 'registered',
      plane: 'branch',
      charter: 'Patient, precise, never promises what banking rules cannot deliver.',
      source: 'prompts/branch-assistant/core.md',
      reason: null,
      blockedBy: null,
      placeholders: ['partyName'],
      editableLayers: 'not-published',
      versionCount: 2,
      versionIds: ['v1', 'v2'],
      coreChars: 4200,
    },
    {
      id: 'collections-outbound',
      status: 'pending',
      plane: 'collections',
      charter: null,
      source: null,
      reason: 'Awaiting legal sign-off on wording.',
      blockedBy: 'legal',
      placeholders: [],
      editableLayers: 'not-published',
      versionCount: 0,
      versionIds: [],
      coreChars: 0,
    },
  ],
}

const detailFixture = {
  id: 'branch-assistant',
  status: 'registered',
  plane: 'branch',
  charter: 'Patient, precise, never promises what banking rules cannot deliver.',
  source: 'prompts/branch-assistant/core.md',
  reason: null,
  blockedBy: null,
  placeholders: ['partyName'],
  editableLayers: 'not-published',
  versions: [
    { id: 'v1', text: 'You are the branch assistant, version one.', chars: 42 },
    { id: 'v2', text: 'You are the branch assistant, version two.', chars: 42 },
  ],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('the persona mirror reports honest stats, registry coverage, and stays read-only', async ({ page }) => {
  await page.route(PERSONAS, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(listFixture) }))
  await page.route(PERSONA_DETAIL, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailFixture) }))

  await page.goto('/communication')
  await expect(page.getByRole('heading', { name: 'Communication Studio' })).toBeVisible()
  // The locked core is stated with its owner and boundary, not a greyed-out editor.
  await expect(page.getByText(/The prompt core .* is owned by engineering in git/)).toBeVisible()
  // Stats are derived from the same rows the table shows: 2 personas, 1 registered,
  // 0 published style layers, 2 prompt versions in total.
  const stats = page.locator('.stat-card')
  await expect(stats.nth(0)).toContainText('2')
  await expect(stats.nth(1)).toContainText('1')
  await expect(stats.nth(2)).toContainText('0')
  await expect(stats.nth(3)).toContainText('2')
  // Registry coverage is a human-readable badge per persona, and an unregistered
  // persona carries its reason and blocker instead of pretending to be a draft.
  // exact: true — the row's detail link carries an aria-label that also contains the id.
  await expect(page.getByRole('link', { name: 'branch-assistant', exact: true })).toBeVisible()
  await expect(page.getByText('registered', { exact: true })).toBeVisible()
  await expect(page.getByText('pending', { exact: true })).toBeVisible()
  await expect(page.getByText('not published yet', { exact: true }).first()).toBeVisible()
})

test('the persona detail shows the locked layers and the exact shipping prompt text', async ({ page }) => {
  await page.route(PERSONA_DETAIL, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailFixture) }))

  await page.goto('/communication/branch-assistant')
  await expect(page.getByRole('heading', { name: 'Prompt layers (ADR-0285 D1)' })).toBeVisible()
  // Core: locked, owned by engineering in git. Style and playbook: named with the
  // phase that lands them, not a disabled editor masquerading as unavailable.
  await expect(page.getByText('locked, not editable from any UI')).toBeVisible()
  await expect(page.getByText('Engineering — git, ADR-0148 registry')).toBeVisible()
  await expect(page.getByText('Call scripts and approved answers arrive in phase 3.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open style editor →' })).toBeVisible()
  // The newest version is selected by default; switching versions swaps the text.
  await expect(page.getByText('You are the branch assistant, version two.')).toBeVisible()
  await page.getByRole('button', { name: 'v1', exact: true }).click()
  await expect(page.getByText('You are the branch assistant, version one.')).toBeVisible()
})

test('a missing registry is an honest absence and a failed load recovers on refresh', async ({ page }) => {
  let mode: 'ok' | 'error' | 'unavailable' = 'error'
  await page.route(PERSONAS, route => {
    if (mode === 'error') return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    if (mode === 'unavailable') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ available: false, schemaVersion: null, relatedAdrs: [], personas: [] }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(listFixture) })
  })
  await page.route(PERSONA_DETAIL, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailFixture) }))

  await page.goto('/communication')
  // A transport/5xx failure is an error, not an empty (healthy-looking) table.
  await expect(page.getByText('Failed to load: Communication Studio')).toBeVisible()

  mode = 'unavailable'
  await page.getByRole('button', { name: 'Refresh' }).click()
  // The registry answering "available: false" is a distinct state from an error:
  // the data source is fine and simply holds nothing yet.
  await expect(page.getByText('No data yet: Communication Studio')).toBeVisible()
  await expect(page.getByText('The prompt registry (openbank-libs/governance/prompts) is not available in this build.')).toBeVisible()

  mode = 'ok'
  await page.getByRole('button', { name: 'Refresh' }).click()
  await expect(page.getByRole('link', { name: 'branch-assistant', exact: true })).toBeVisible()

  // The same honesty contract on the detail: an unknown persona is "Nothing found",
  // not an error and not a blank page.
  await page.route(PERSONA_DETAIL, route => route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' }))
  await page.goto('/communication/no-such-persona')
  await expect(page.getByText('The prompt registry does not contain this persona.')).toBeVisible()
})
