// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// E2E evidence for the IAOps flaky-test-hunter surfaces (ADR-0168): the findings list
// (/iaops/flaky-test-hunter), the finding detail (/iaops/flaky-test-hunter/[id]) and
// the failure-state contract of both. Complements flaky-test-trigger.spec.ts, which
// covers the manual-check trigger flow; this spec covers everything the trigger flow
// stubs away (the findings themselves) plus the error/no-data/not-found distinction.
//
// The evidence matrix counts these as e2e coverage for the iaops route group, which
// previously had none outside the trigger flow.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

interface Finding {
  id: string
  checkType: 'RUNBLOCKING_UNIT_MISSING' | 'PACT_LOCAL_VERIFICATION_BLIND_SPOT' | 'PACT_PROVIDER_CLASS_COLLISION' | 'TEST_COUNT_DRIFT'
  severity: 'WARNING' | 'CRITICAL'
  detectedAt: string
  title: string
  component: string
  filePath: string
  rawMetricValue: number
  threshold: number
  rootCause: string | null
  proposalUrl: string | null
  proposedFixDiff: string | null
  status: 'OPEN' | 'DIAGNOSED' | 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'RESOLVED'
  diagnosedAt: string | null
  proposedAt: string | null
}

const OPEN_CRITICAL: Finding = {
  id: 'f-100',
  checkType: 'TEST_COUNT_DRIFT',
  severity: 'CRITICAL',
  detectedAt: '2026-10-12T06:30:00Z',
  title: 'sepa-payment suite shrank by 12 tests against the baseline',
  component: 'openbank-sepa-payment',
  filePath: 'openbank-sepa-payment/src/test/kotlin/com/openbank/sepa/SepaPaymentWorkflowTest.kt',
  rawMetricValue: 12,
  threshold: 5,
  rootCause: null,
  proposalUrl: null,
  proposedFixDiff: null,
  status: 'OPEN',
  diagnosedAt: null,
  proposedAt: null,
}

const RESOLVED_WARNING: Finding = {
  id: 'f-200',
  checkType: 'RUNBLOCKING_UNIT_MISSING',
  severity: 'WARNING',
  detectedAt: '2026-10-05T06:30:00Z',
  title: 'scheduled sweep body lacks explicit Unit return type',
  component: 'openbank-clearing',
  filePath: 'openbank-clearing/src/test/kotlin/com/openbank/clearing/ClearingSchedulerTest.kt',
  rawMetricValue: 3,
  threshold: 0,
  rootCause: 'JUnit5 ignores a non-Unit return type; the sweep body never asserted.',
  proposalUrl: 'https://github.com/JiRaska/open-bank-oss/pull/12799',
  proposedFixDiff: '-  fun sweep() = runBlocking { sweepOnce() }\n+  fun sweep(): Unit = runBlocking { sweepOnce() }',
  status: 'RESOLVED',
  diagnosedAt: '2026-10-05T07:00:00Z',
  proposedAt: '2026-10-05T08:00:00Z',
}

const DIAGNOSED: Finding = {
  ...RESOLVED_WARNING,
  id: 'f-200-detail',
  status: 'DIAGNOSED',
  rootCause: 'JUnit5 ignores a non-Unit return type; the sweep body never asserted.',
  proposedFixDiff: null,
  proposalUrl: null,
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  // Default: the findings endpoint answers for BOTH the list and the detail fetch —
  // routing by URL keeps every test's stubbing explicit instead of order-dependent.
  await page.route('**/api/flaky-test-hunter/findings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ findings: [OPEN_CRITICAL, RESOLVED_WARNING], available: true }),
  }))
})

test('findings list renders severity, check type and status with matching stat cards', async ({ page }) => {
  await page.goto('/iaops/flaky-test-hunter')

  // Stat cards summarise the same payload the table shows — count both directions.
  await expect(page.locator('.stat-card', { hasText: 'Active findings' }).locator('.stat-value')).toHaveText('2')
  await expect(page.locator('.stat-card', { hasText: 'Critical' }).locator('.stat-value')).toHaveText('1')
  await expect(page.locator('.stat-card', { hasText: 'Open' }).locator('.stat-value')).toHaveText('1')

  await expect(page.getByRole('table')).toBeVisible()
  await expect(page.getByRole('row', { name: /sepa-payment suite shrank/ })).toContainText('Test count drift')
  await expect(page.getByRole('row', { name: /sepa-payment suite shrank/ })).toContainText('CRITICAL')
  await expect(page.getByRole('row', { name: /sepa-payment suite shrank/ })).toContainText('OPEN')
  await expect(page.getByRole('row', { name: /scheduled sweep body/ })).toContainText('runBlocking missing Unit')
  await expect(page.getByRole('row', { name: /scheduled sweep body/ })).toContainText('RESOLVED')

  const detail = page.getByRole('link', { name: /Detail/ }).first()
  await expect(detail).toHaveAttribute('href', `/iaops/flaky-test-hunter/${encodeURIComponent(OPEN_CRITICAL.id)}`)
})

test('finding detail renders root cause and proposed fix diff with the proposal link', async ({ page }) => {
  await page.unroute('**/api/flaky-test-hunter/findings')
  await page.route(`**/api/flaky-test-hunter/findings/${encodeURIComponent('f-200-detail')}`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(DIAGNOSED),
  }))

  await page.goto(`/iaops/flaky-test-hunter/${encodeURIComponent('f-200-detail')}`)

  await expect(page.getByRole('heading', { name: /scheduled sweep body/ })).toBeVisible()
  await expect(page.getByText('Root cause')).toBeVisible()
  await expect(page.getByText(/JUnit5 ignores a non-Unit return type/)).toBeVisible()
  await expect(page.getByText('Measured value / threshold')).toBeVisible()
  await expect(page.getByText('3 / 0')).toBeVisible()
  // DIAGNOSED carries no proposed fix — the section must not render an empty card.
  await expect(page.getByText('Proposed fix')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Back to findings/ })).toHaveAttribute('href', '/iaops/flaky-test-hunter')
})

test('detail renders the proposed-fix diff and proposal PR link when phase-3 produced them', async ({ page }) => {
  await page.unroute('**/api/flaky-test-hunter/findings')
  await page.route(`**/api/flaky-test-hunter/findings/${encodeURIComponent(RESOLVED_WARNING.id)}`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(RESOLVED_WARNING),
  }))

  await page.goto(`/iaops/flaky-test-hunter/${encodeURIComponent(RESOLVED_WARNING.id)}`)

  await expect(page.getByText('Proposed fix')).toBeVisible()
  await expect(page.locator('pre')).toContainText('fun sweep(): Unit = runBlocking')
  await expect(page.getByRole('link', { name: 'View PR →' })).toHaveAttribute('href', RESOLVED_WARNING.proposalUrl!)
})

test('a failed findings load renders the error state, never the no-data panel', async ({ page }) => {
  await page.unroute('**/api/flaky-test-hunter/findings')
  await page.route('**/api/flaky-test-hunter/findings', route => route.fulfill({ status: 500, body: 'boom' }))

  await page.goto('/iaops/flaky-test-hunter')

  await expect(page.getByText(/Failed to load/)).toBeVisible()
  // The no-data panel would claim the sweep found nothing — that reading must be impossible here.
  await expect(page.getByText('No data yet')).toHaveCount(0)
})

test('available:false renders unavailable findings without claiming a verified zero', async ({ page }) => {
  await page.unroute('**/api/flaky-test-hunter/findings')
  await page.route('**/api/flaky-test-hunter/findings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ findings: [], available: false }),
  }))

  await page.goto('/iaops/flaky-test-hunter')

  await expect(page.getByText('Findings and workflow completion status are unavailable.')).toBeVisible()
  await expect(page.getByText('No data yet')).toHaveCount(0)
  await expect(page.getByText('Active findings').locator('..').locator('.stat-value')).toHaveText('—')
})

test('a missing finding renders not-found, not the load-failure state', async ({ page }) => {
  await page.unroute('**/api/flaky-test-hunter/findings')
  await page.route(`**/api/flaky-test-hunter/findings/${encodeURIComponent('f-missing')}`, route =>
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not_found' }) }))

  await page.goto(`/iaops/flaky-test-hunter/${encodeURIComponent('f-missing')}`)

  await expect(page.getByText('Nothing found')).toBeVisible()
  await expect(page.getByText(/Failed to load/)).toHaveCount(0)
})
