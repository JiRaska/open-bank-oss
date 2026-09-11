// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const check = {
  id: 'check-theme-1',
  name: 'Example Match Ltd',
  entityType: 'ORGANIZATION',
  status: 'HIT',
  overallScore: 0.91,
  checkedLists: ['EU'],
  matches: [{ listType: 'EU', matchType: 'EXACT', matchScore: 0.91, matchedName: 'Example Match Ltd', programs: ['TEST'] }],
  checkedAt: '2026-09-01T12:00:00Z',
}

const pepList = {
  id: 'pep-eu',
  listType: 'PEP_EU',
  displayName: 'European PEP List',
  sourceUrl: 'https://example.test/pep',
  enabled: true,
  lastUpdatedAt: '2026-09-01T10:00:00Z',
  lastEntryCount: 120,
  cronHour: 3,
  cronMinute: 0,
  cronDays: 'MON,TUE,WED,THU,FRI',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/sanctions/checks', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify([check]) }))
  await page.route('**/api/sanctions/lists', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify([pepList]) }))
  await page.route('**/api/sanctions/approvals', route => route.fulfill({ contentType: 'application/json', body: '[]' }))
})

test.describe('/sanctions — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves compliance evidence and WCAG A/AA`, async ({ page }) => {
      await page.goto('/sanctions')
      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByRole('heading', { name: /Sanctions Screening|Prověření sankcí/ })).toBeVisible()
      await expect(page.getByText(check.name)).toBeVisible()
      await expect(page.getByText(/sanctions match.*require immediate attention|sankční shoda.*vyžaduje okamžitou pozornost/i)).toBeVisible()

      await page.getByRole('button', { name: /Manual Search|Manuální vyhledávání/ }).click()
      await expect(page.getByRole('checkbox', { name: pepList.displayName })).toBeChecked()
      await expect(page.getByText('PEP', { exact: true })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
