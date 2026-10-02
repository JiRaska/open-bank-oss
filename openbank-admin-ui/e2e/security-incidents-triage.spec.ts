// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const base = {
  description: 'Customer payments were delayed while responders isolated the dependency.',
  category: 'AVAILABILITY',
  affectedServices: ['payment-service'],
  detectedAt: '2026-09-09T08:00:00Z',
  reportedAt: '2026-09-09T08:05:00Z',
  containedAt: null,
  resolvedAt: null,
  rtoMinutes: null,
  rpoMinutes: 0,
  reportedToRegulator: false,
  regulatoryReportId: null,
  assignedTo: 'incident-commander',
  createdAt: '2026-09-09T08:05:00Z',
  updatedAt: '2026-09-09T08:10:00Z',
}
const incidents = [
  { ...base, id: '11111111-1111-1111-1111-111111111111', title: 'Payment dependency outage', severity: 'P1_CRITICAL', status: 'INVESTIGATING' },
  { ...base, id: '22222222-2222-2222-2222-222222222222', title: 'Document latency', severity: 'P3_MEDIUM', status: 'CONTAINED', affectedServices: ['document-service'] },
  { ...base, id: '33333333-3333-3333-3333-333333333333', title: 'Closed access event', severity: 'P4_LOW', status: 'CLOSED', reportedToRegulator: true, regulatoryReportId: 'DORA-2026-009', assignedTo: null },
]

test('triages verified DORA incidents and preserves the snapshot after malformed refresh', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  let malformed = false
  let impactMalformed = false
  await page.route('**/api/security/incidents', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ available: true, incidents: malformed ? [{ ...incidents[0], detectedAt: 'not-a-date' }] : incidents }),
  }))
  await page.route('**/api/context/incidents/*/impact?*', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(impactMalformed
      ? { affectedByType: { SERVICE: 1 }, total: 2, drilldownAvailable: false }
      : { affectedByType: { SERVICE: 1 }, total: 1, drilldownAvailable: false, projectionStatus: 'PARTIAL' }),
  }))

  await page.goto('/security/incidents')
  const register = page.getByLabel('Scrollable ICT incident register')
  await expect(register.getByText('Payment dependency outage', { exact: true })).toBeVisible()
  await expect(page.getByText('3', { exact: true }).first()).toBeVisible()
  await expect(register.getByText('P1 · Critical')).toBeVisible()
  await expect(page.getByText('No report ID').first()).toBeVisible()

  const impactForm = page.getByRole('button', { name: 'View reported scope' }).locator('xpath=ancestor::form')
  await expect.poll(async () => impactForm.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    return bounds.left >= 0 && bounds.right <= document.documentElement.clientWidth
  })).toBe(true)
  await impactForm.getByLabel('Incident', { exact: true }).selectOption(incidents[0].id)
  await impactForm.getByLabel('Case ID', { exact: true }).fill('case-7')
  await page.getByRole('button', { name: 'View reported scope' }).click()
  await expect(page.getByText('Projected links: 1')).toBeVisible()
  await expect(page.getByText('SERVICE: 1')).toBeVisible()
  await expect(page.getByText('Partial result: counts cover only the retrieved slice.')).toBeVisible()
  await expect(page.getByRole('img', { name: 'Aggregate reported incident scope map' })).toBeVisible()

  impactMalformed = true
  await page.getByRole('button', { name: 'View reported scope' }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'Impact could not be verified safely.' })).toBeVisible()
  await expect(page.getByText('Projected links: 1')).toHaveCount(0)

  await page.getByLabel('Filter by severity').selectOption('P3_MEDIUM')
  await expect(page.getByRole('status')).toContainText('1 of 3 incidents')
  await expect(register.getByText('Document latency', { exact: true })).toBeVisible()
  await expect(register.getByText('Payment dependency outage', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Clear' }).click()

  await page.getByLabel('Search incidents').fill('document-service')
  await expect(register.getByText('Document latency', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Clear' }).click()
  await page.getByText('What happened').first().click()
  await expect(page.getByText(/Customer payments were delayed/).first()).toBeVisible()

  malformed = true
  await page.getByRole('button', { name: 'Refresh ICT incidents' }).click()
  const evidenceAlert = page.getByRole('alert').filter({ hasText: 'The register could not be verified' })
  await expect(evidenceAlert).toContainText('could not be verified')
  await expect(evidenceAlert).toContainText('last successfully verified snapshot')
  await expect(register.getByText('Payment dependency outage', { exact: true })).toBeVisible()
  await expect(page.getByRole('status')).toContainText('3 of 3 incidents')

  await page.unroute('**/api/security/incidents')
  await page.route('**/api/security/incidents', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ available: false, reason: 'unauthorized' }),
  }))
  await page.getByRole('button', { name: 'Refresh ICT incidents' }).click()
  await expect(register.getByText('Payment dependency outage', { exact: true })).toHaveCount(0)
  await expect(page.getByText('last successfully verified snapshot')).toHaveCount(0)
  await expect(page.getByText(/Your role cannot view this register/)).toBeVisible()
})
