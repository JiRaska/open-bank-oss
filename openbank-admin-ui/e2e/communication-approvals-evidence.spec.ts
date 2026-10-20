// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

// Communication style approvals workbench (ADR-0285 D6) — the four-eyes queue where a
// DIFFERENT approver than the maker decides the publish pauses the style editor creates.
// The phase-2 contract this spec pins down:
//  - the checker (ROLE_COMMS_APPROVER) can actually reach the page: the edge gate admits
//    approvals:view OR communication:style:decide for /approvals/communication, because
//    the checker holds only the latter — without the OR-set the queue their decision
//    lands in would be unreachable;
//  - the queue is honest about emptiness — an empty list is "No pending approvals.", not
//    an empty table that looks broken;
//  - approve/reject name their outcomes in UI terms, and a refused decision (self-approval,
//    already decided) shows the sanitized explanation, never the raw backend message;
//  - a failed load is a named unavailability state with retry, not a stuck spinner.

const APPROVALS = '**/api/svc/communication-service/api/v1/communications/approvals**'

const pendingItem = {
  id: 'pa-1',
  action: 'commstyle.publish',
  resourceId: 'sv-200',
  status: 'PENDING',
  makerId: 'maker-7',
  createdAt: '2026-10-14T12:00:00Z',
  decidedBy: null,
}

test('the checker reaches the queue, sees the pending publish pause, and approves it', async ({ page, context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_APPROVER'])
  let queue: unknown[] = [pendingItem]
  await page.route(APPROVALS, route => {
    if (route.request().method() === 'PATCH') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...pendingItem, status: 'APPROVED', decidedBy: 'checker-1' }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(queue) })
  })

  await page.goto('/approvals/communication')
  await expect(page.getByRole('heading', { name: 'Style approvals (four-eyes)' })).toBeVisible()
  // The row names what is being decided: the paused publish action and its maker, so the
  // checker can verify the hand-off matches what the maker pasted as the draft id.
  await expect(page.getByText('commstyle.publish')).toBeVisible()
  await expect(page.getByText('maker-7')).toBeVisible()

  await page.getByRole('button', { name: 'Approve' }).click()
  // Approval is not the publish: the pause only clears, the maker must retry the call.
  await expect(page.getByText('Approved — the maker must now retry the publish call.')).toBeVisible()

  // The queue reloads after the decision: the decided item no longer renders its buttons.
  queue = []
  await page.getByRole('button', { name: 'Approve' }).click()
  await expect(page.getByText('No pending approvals.')).toBeVisible()
})

test('a refused decision shows the sanitized explanation, never the raw backend error', async ({ page, context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_APPROVER'])
  await page.route(APPROVALS, route => {
    if (route.request().method() === 'PATCH') {
      // SelfApprovalNotAllowedException / already-decided land here as a refusal.
      return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'SelfApprovalNotAllowedException: maker cannot decide own draft' }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([pendingItem]) })
  })

  await page.goto('/approvals/communication')
  await expect(page.getByText('commstyle.publish')).toBeVisible()
  await page.getByRole('button', { name: 'Reject' }).click()

  // The sanitized message names the two realistic causes; the raw backend exception text
  // must not leak into the UI.
  await expect(page.getByText('The decision failed. You may have created this draft yourself, or it was already decided.')).toBeVisible()
  await expect(page.getByText(/SelfApprovalNotAllowedException/)).not.toBeVisible()
})

test('queue load failure is a named unavailability with retry, and roles without decide stay out', async ({ page, context, baseURL }) => {
  // Edge gate: ROLE_OPERATOR holds approvals:view (passes the generic queue guard) but
  // not communication:style:decide — the in-page AuthGuard names the missing permission
  // instead of rendering the queue.
  await signInWithRoles(context, baseURL!, ['ROLE_OPERATOR'])
  await page.goto('/approvals/communication')
  await expect(page.getByText('Access denied')).toBeVisible()
  await expect(page.getByText('Required permission: communication:style:decide')).toBeVisible()

  // Viewer lacks both halves of the OR-set: the edge gate refuses before any page render.
  await context.clearCookies()
  await signInWithRoles(context, baseURL!, ['ROLE_VIEWER'])
  await page.goto('/approvals/communication')
  await expect(page).toHaveURL(/\/auth\/forbidden/)

  // Checker with a failing backend: named unavailability, retry recovers.
  await context.clearCookies()
  await signInWithRoles(context, baseURL!, ['ROLE_COMMS_APPROVER'])
  let mode: 'error' | 'ok' | 'empty' = 'error'
  await page.route(APPROVALS, route => {
    if (route.request().method() !== 'GET') return route.fallback()
    if (mode === 'error') return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    if (mode === 'empty') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([pendingItem]) })
  })

  await page.goto('/approvals/communication')
  await expect(page.getByText('Failed to load: approval queue')).toBeVisible()
  mode = 'ok'
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByText('commstyle.publish')).toBeVisible()
})
