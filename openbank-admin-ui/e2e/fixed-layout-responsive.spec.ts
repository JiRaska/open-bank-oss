// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ page, context, baseURL }) => {
  await page.setViewportSize({ width: 320, height: 760 })
  await signInAsOperator(context, baseURL!)
})

test('security evidence stacks while its wide table remains locally scrollable', async ({ page }) => {
  const categories = [
    'A01_BROKEN_ACCESS_CONTROL', 'A02_CRYPTOGRAPHIC_FAILURES', 'A03_INJECTION',
    'A04_INSECURE_DESIGN', 'A05_SECURITY_MISCONFIGURATION', 'A06_VULNERABLE_COMPONENTS',
    'A07_AUTH_FAILURES', 'A08_SOFTWARE_INTEGRITY_FAILURES', 'A09_LOGGING_MONITORING_FAILURES', 'A10_SSRF',
  ]
  await page.route('**/api/security', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      report: {
        reportId: 'b6ea92e9-c4b4-48af-88f5-b23705db5ef8', generatedAt: '2026-09-10T02:01:00Z',
        totalServices: 1, reachableServices: 1, platformScore: 95, platformGrade: 'A+',
        criticalFindings: 0, highFindings: 0,
        owaspCoverage: Object.fromEntries(categories.map(category => [category, 0])),
        complianceStatus: { PSD2_SCA: true, OWASP_TOP10: true },
        serviceResults: [{
          serviceName: 'account-service', serviceUrl: 'https://example.test', scannedAt: '2026-09-10T02:00:00Z',
          durationMs: 125, reachable: true, findings: [], score: 95, grade: 'A+',
          headersPresent: { 'content-security-policy': true }, openApiAvailable: true,
        }],
      },
    }),
  }))

  await page.goto('/security')
  const layout = page.getByTestId('security-results-layout')
  await expect(layout).toBeVisible()
  expect(await layout.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  await expect(page.getByLabel('Scrollable security scan results')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('process education keeps its three lenses readable on mobile', async ({ page }) => {
  await page.goto('/docs/auth-flow')
  const layout = page.getByTestId('process-view-layout')
  await expect(layout).toBeVisible()
  expect(await layout.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  await page.getByRole('button', { name: /Tokeny/ }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
