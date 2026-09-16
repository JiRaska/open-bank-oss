// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('dashboard does not present zero fleet metrics before the first verified response', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let releaseHealth!: () => void
  let markRequested!: () => void
  const heldHealth = new Promise<void>(resolve => { releaseHealth = resolve })
  const healthRequested = new Promise<void>(resolve => { markRequested = resolve })
  await page.route('**/api/services/governance', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ available: true, timestamp: '2026-09-16T10:00:00Z', items: [
      { serviceName: 'account-service', dataDomain: 'core' },
    ] }),
  }))
  await page.route('**/api/services/health', async route => {
    markRequested()
    await heldHealth
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ services: [
      { name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 },
    ] }) })
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await healthRequested
  await expect(page.getByRole('heading', { name: 'Work queues' })).toBeVisible()
  await expect(page.getByText('0/0', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('status', { name: 'Platform evidence is loading' })).toBeVisible()

  releaseHealth()
  await expect(page.getByText('1/1', { exact: true })).toHaveCount(2)
  await expect(page.getByRole('status', { name: 'Platform evidence is loading' })).toHaveCount(0)
})

test('dashboard keeps the retry state truthful after a failed refresh', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let releaseRetry!: () => void
  let markRetryRequested!: () => void
  const heldRetry = new Promise<void>(resolve => { releaseRetry = resolve })
  const retryRequested = new Promise<void>(resolve => { markRetryRequested = resolve })
  await page.route('**/api/services/governance', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ available: true, timestamp: '2026-09-16T10:00:00Z', items: [
      { serviceName: 'account-service', dataDomain: 'core' },
    ] }),
  }))
  let healthCalls = 0
  await page.route('**/api/services/health', async route => {
    healthCalls += 1
    if (healthCalls === 2) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    if (healthCalls === 3) {
      markRetryRequested()
      await heldRetry
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ services: [
      { name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 },
    ] }) })
  })

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText('1/1', { exact: true })).toHaveCount(2)
  await page.getByRole('button', { name: 'Refresh platform overview' }).click()
  await expect(page.getByText('Current platform state cannot be verified')).toBeVisible()
  await page.getByRole('button', { name: 'Try again' }).click()
  await retryRequested
  await expect(page.getByRole('status', { name: 'Platform evidence is loading' })).toBeVisible()
  await expect(page.getByText('0/0', { exact: true })).toHaveCount(0)

  releaseRetry()
  await expect(page.getByText('1/1', { exact: true })).toHaveCount(2)
  expect(healthCalls).toBe(3)
})

test('work queues wait for the operator role instead of looking empty or mislabelled', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let releaseSession!: () => void
  let markSessionRequested!: () => void
  const heldSession = new Promise<void>(resolve => { releaseSession = resolve })
  const sessionRequested = new Promise<void>(resolve => { markSessionRequested = resolve })
  await page.route('**/api/auth/session', async route => {
    markSessionRequested()
    await heldSession
    await route.continue()
  })
  await page.route('**/api/services/governance', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ available: true, timestamp: '2026-09-16T10:00:00Z', items: [
      { serviceName: 'account-service', dataDomain: 'core' },
    ] }),
  }))
  await page.route('**/api/services/health', route => route.fulfill({
    contentType: 'application/json', body: '{"services":[]}',
  }))

  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await sessionRequested
  const workQueues = page.locator('section[aria-labelledby="workspace-heading"]')
  await expect(workQueues.getByRole('status', { name: 'Work queues are loading' })).toBeVisible()
  await expect(page.getByText('My workspace', { exact: true })).toBeVisible()
  await expect(workQueues.getByRole('link')).toHaveCount(0)

  releaseSession()
  await expect(workQueues.getByRole('status', { name: 'Work queues are loading' })).toHaveCount(0)
  await expect(workQueues.getByRole('link', { name: 'Test Intelligence' })).toBeVisible()
})
