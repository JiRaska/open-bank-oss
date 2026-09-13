// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/flaky-test-hunter/findings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ findings: [], available: true }),
  }))
})

test('renders a 202 as admission only, never as a completed or currently running workflow', async ({ page }) => {
  await page.route('**/api/iaops/flaky-test-hunter/trigger', async route => {
    expect(route.request().method()).toBe('POST')
    const body = route.request().postDataJSON() as { requestedOn: string }
    expect(body.requestedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ workflowId: `flaky-test-hunter-check-operator_manual-${body.requestedOn}` }),
    })
  })

  await page.goto('/iaops/flaky-test-hunter')
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()

  const admission = page.getByRole('status')
  await expect(admission).toContainText(/does not prove whether the workflow is running or already complete|nedokládá, zda workflow právě běží, nebo už skončilo/)
  await expect(page.getByText(/Accepted|Přijato/, { exact: true })).toBeVisible()
  await expect(page.getByText(/Check completed|Kontrola dokončena/)).toHaveCount(0)
  await expect(page.getByText(/Pending workflow|Čekající workflow/)).toHaveCount(0)
})

test('persists an ambiguous attempt key across reload and safely recovers the same workflow', async ({ page }) => {
  const requestedDays: string[] = []
  let attempts = 0
  await page.route('**/api/iaops/flaky-test-hunter/trigger', async route => {
    const body = route.request().postDataJSON() as { requestedOn: string }
    requestedDays.push(body.requestedOn)
    attempts += 1
    if (attempts === 1) {
      await route.fulfill({
        status: 502,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'admission_outcome_unknown',
          cause: 'network',
          requestedOn: body.requestedOn,
        }),
      })
      return
    }
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ workflowId: `flaky-test-hunter-check-operator_manual-${body.requestedOn}` }),
    })
  })

  await page.goto('/iaops/flaky-test-hunter')
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()
  await expect(page.getByRole('status')).toContainText(/Retrying is safe|Opakování je bezpečné/)

  await page.reload()
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()
  await expect(page.getByRole('status')).toContainText(/does not prove whether the workflow is running or already complete|nedokládá, zda workflow právě běží, nebo už skončilo/)

  expect(requestedDays).toHaveLength(2)
  expect(requestedDays[1]).toBe(requestedDays[0])
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem('openbank.flaky-test-hunter.trigger.requested-on'))).toBeNull()
})

test('fails closed without legacy fallback while the backend expand is still rolling out', async ({ page }) => {
  let requestedOn = ''
  await page.route('**/api/iaops/flaky-test-hunter/trigger', async route => {
    requestedOn = (route.request().postDataJSON() as { requestedOn: string }).requestedOn
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({
        error: 'idempotent_admission_not_supported',
        requestedOn,
        upstreamStatus: 404,
      }),
    })
  })

  await page.goto('/iaops/flaky-test-hunter')
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()

  await expect(page.getByRole('status')).toContainText(/does not support idempotent admission yet|ještě nepodporuje idempotentní přijetí/)
  await expect(page.getByText(/Accepted|Přijato/, { exact: true })).toHaveCount(0)
  await expect.poll(() => page.evaluate(key => window.localStorage.getItem(key), 'openbank.flaky-test-hunter.trigger.requested-on')).toBe(requestedOn)
})

test('keeps a previous-day recovery key across reauthentication', async ({ page }) => {
  const recoveryDate = new Date()
  recoveryDate.setUTCDate(recoveryDate.getUTCDate() - 1)
  const recoveryDay = recoveryDate.toISOString().slice(0, 10)
  await page.addInitScript(({ key, value }) => window.localStorage.setItem(key, value), {
    key: 'openbank.flaky-test-hunter.trigger.requested-on',
    value: recoveryDay,
  })
  const requestedDays: string[] = []
  let attempts = 0
  await page.route('**/api/iaops/flaky-test-hunter/trigger', async route => {
    const requestedOn = (route.request().postDataJSON() as { requestedOn: string }).requestedOn
    requestedDays.push(requestedOn)
    attempts += 1
    if (attempts === 1) {
      await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ error: 'unauthorized' }) })
      return
    }
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ workflowId: `flaky-test-hunter-check-operator_manual-${requestedOn}` }),
    })
  })

  await page.goto('/iaops/flaky-test-hunter')
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()
  await expect(page.getByText(/After access is restored|Po obnovení přístupu/)).toBeVisible()
  await expect.poll(() => page.evaluate(key => window.localStorage.getItem(key), 'openbank.flaky-test-hunter.trigger.requested-on')).toBe(recoveryDay)

  await page.reload()
  await page.getByRole('button', { name: /Run check now|Spustit kontrolu/ }).click()
  await expect(page.getByRole('status')).toContainText(/does not prove whether the workflow is running or already complete|nedokládá, zda workflow právě běží, nebo už skončilo/)
  expect(requestedDays).toEqual([recoveryDay, recoveryDay])
  await expect.poll(() => page.evaluate(key => window.localStorage.getItem(key), 'openbank.flaky-test-hunter.trigger.requested-on')).toBeNull()
})
