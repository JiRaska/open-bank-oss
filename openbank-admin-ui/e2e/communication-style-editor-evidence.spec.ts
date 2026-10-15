// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

// Communication Studio style editor (ADR-0285 D6/D7, phase 2) — the WRITE path on top of
// the read-only mirror. Two roles work the same draft: the maker (ROLE_COMMS_EDITOR)
// drafts and submits for review; the checker (ROLE_COMMS_APPROVER) initiates publish,
// which is four-eyes-paused until a DIFFERENT approver decides it on the workbench. The
// phase-2 contract this spec pins down:
//  - the maker cannot even see the publish controls (permission-gated section, not a
//    disabled button that pretends the action exists);
//  - the form is honest about readiness — Save stays disabled until tone, formality and
//    form of address are all filled AND the published base generation has loaded;
//  - the checker sees the server's decisions verbatim: a 202 is a pause (with an approval
//    id to retry with), a 409 distinguishes "your base moved" from a plain refusal, and
//    only a 200 is "Published.";
//  - a failed load is a named unavailability state with recovery, never a silently empty
//    editor;
//  - the edge gate refuses a role without communication:style:propose before any editor
//    UI renders.

const EDITOR_STATE = '**/api/svc/communication-service/api/v2/personas/*/editor-state'
const STYLE_VERSIONS = '**/api/svc/communication-service/api/v2/personas/*/style-versions'
const SUBMIT_REVIEW = '**/api/svc/communication-service/api/v2/personas/style-versions/*/submit'
const PUBLISH = '**/api/svc/communication-service/api/v2/personas/style-versions/*/publish'

const noPublishedState = { basePublishedVersion: 0, published: null }

const publishedState = {
  basePublishedVersion: 2,
  published: {
    styleVersion: 2,
    tone: 'warm',
    formality: 'semi-formal',
    formOfAddress: 'first-name',
    signature: null,
    maxLength: null,
    uiMessages: {},
    preferredTerms: {},
    forbiddenTerms: [],
  },
}

test('the maker drafts, saves and submits for review — publish controls stay out of reach', async ({ page, context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_EDITOR'])
  await page.route(EDITOR_STATE, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(noPublishedState) }))
  await page.route(STYLE_VERSIONS, route => route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'sv-100', version: 1, status: 'DRAFT' }) }))
  await page.route(SUBMIT_REVIEW, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'sv-100', version: 1, status: 'IN_REVIEW' }) }))

  await page.goto('/communication/edit/branch-assistant')
  await expect(page.getByRole('heading', { name: 'Style editor' })).toBeVisible()

  // The maker section exists; the checker section is not rendered at all for this role —
  // not a greyed-out Publish button pretending the capability is merely unavailable.
  await expect(page.getByText('Draft (maker)')).toBeVisible()
  await expect(page.getByText('Publish (checker)')).not.toBeVisible()

  // Save is disabled until the three required fields are filled AND the published base
  // generation has loaded — an editor that saved before the base loaded would fork from
  // a version it never saw.
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeDisabled()
  await page.getByLabel('Tone', { exact: true }).fill('patient and precise')
  await page.getByLabel('Formality').fill('formal, vykání')
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeDisabled()
  await page.getByLabel('Form of address').fill('formal "you"')
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeEnabled()

  await page.getByRole('button', { name: 'Save draft' }).click()
  // The draft now owns the form: inputs freeze so the maker edits the draft through the
  // version lifecycle, not by mutating fields behind it.
  await expect(page.getByText('Version 1 —')).toBeVisible()
  await expect(page.getByText('DRAFT', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Tone', { exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Save draft' })).not.toBeVisible()

  await page.getByRole('button', { name: 'Submit for review' }).click()
  // IN_REVIEW carries the draft id the checker will paste into the publish box — the
  // hand-off is explicit, not implied.
  await expect(page.getByText('IN_REVIEW')).toBeVisible()
  await expect(page.getByText('draft id for an approver:')).toBeVisible()
  await expect(page.locator('.mono', { hasText: 'sv-100' })).toBeVisible()
})

test('the checker publish flow names every server decision: pause, stale base, published', async ({ page, context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_APPROVER'])
  let publishMode: 'paused' | 'stale' | 'ok' = 'paused'
  await page.route(EDITOR_STATE, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(publishedState) }))
  await page.route(PUBLISH, route => {
    if (publishMode === 'paused') return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ id: 'pa-1' }) })
    if (publishMode === 'stale') return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'published style changed' }) })
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/communication/edit/branch-assistant')
  await expect(page.getByRole('heading', { name: 'Style editor' })).toBeVisible()
  // The checker sees the publish contract and the published base rendered in the maker
  // fields, but cannot draft: the maker section is permission-gated the same way.
  await expect(page.getByText('Publish (checker)')).toBeVisible()
  await expect(page.getByText('Draft (maker)')).not.toBeVisible()
  // The maker form (tone, formality, …) lives inside the propose-gated card, so the
  // checker never sees it — their only input on this page is the draft id.

  await page.getByLabel('Draft id').fill('sv-200')
  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  // A 202 is a four-eyes PAUSE, not a success and not a failure: the page must say so and
  // offer the approval-id retry, because the pause is meaningless without it.
  await expect(page.getByText('Publish paused — waiting for a different approver on the workbench.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retry with approval id' })).toBeVisible()

  publishMode = 'stale'
  await page.getByRole('button', { name: 'Retry with approval id' }).click()
  // 409 with a moved base is actionable advice ("reload and prepare a new draft"), not a
  // generic refusal — the two failure shapes must not blur into one.
  await expect(page.getByText('This draft is based on an older version. Reload the current copy and prepare a new draft.')).toBeVisible()

  publishMode = 'ok'
  await page.getByRole('button', { name: 'Publish', exact: true }).click()
  await expect(page.getByText('Published.')).toBeVisible()
  // The pause is over: the retry affordance disappears once the publish lands.
  await expect(page.getByRole('button', { name: 'Retry with approval id' })).not.toBeVisible()
})

test('a failed editor-state load is a named unavailability with recovery, and the edge gate denies without the permission', async ({ page, context, baseURL }) => {
  // Edge gate first: ROLE_VIEWER carries no communication:style:propose, so the request is
  // refused before any editor UI renders.
  await signInWithRoles(context, baseURL!, ['ROLE_VIEWER'])
  await page.goto('/communication/edit/branch-assistant')
  await expect(page).toHaveURL(/\/auth\/forbidden/)

  // Then the editor role with a failing backend.
  await context.clearCookies()
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_EDITOR'])
  let stateMode: 'error' | 'ok' = 'error'
  await page.route(EDITOR_STATE, route => {
    if (stateMode === 'error') return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(noPublishedState) })
  })

  await page.goto('/communication/edit/branch-assistant')
  // A transport/5xx failure names the service and the feature instead of rendering an
  // empty, healthy-looking editor.
  await expect(page.getByText('Communication is not responding')).toBeVisible()
  await expect(page.getByText('style editor')).toBeVisible()

  stateMode = 'ok'
  await page.getByRole('button', { name: 'Try again' }).click()
  // Recovery lands on the real form, with the save button still gated on the loaded base.
  await expect(page.getByText('Draft (maker)')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeDisabled()
})
