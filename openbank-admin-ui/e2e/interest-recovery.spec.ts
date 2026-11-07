// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const ACCRUALS = [
  {
    id: 'accrual-1',
    accountId: '11111111-1111-4111-8111-111111111111',
    accrualDate: '2026-10-01',
    accruedAmount: 42.5041,
    currency: 'CZK',
    rate: 0.0365,
    dayCount: 'ACT_365',
    status: 'ACCRUING',
  },
  {
    id: 'accrual-2',
    accountId: '11111111-1111-4111-8111-111111111111',
    accrualDate: '2026-09-30',
    accruedAmount: 41.9178,
    currency: 'CZK',
    rate: 0.0365,
    dayCount: 'ACT_365',
    status: 'CAPITALIZED',
  },
  {
    id: 'accrual-3',
    accountId: '22222222-2222-4222-8222-222222222222',
    accrualDate: '2026-10-01',
    accruedAmount: 7.5,
    currency: 'CZK',
    rate: 0.02,
    dayCount: 'ACT_360',
    status: 'REVERSED',
  },
]

const ACCRUALS_URL = '**/api/svc/interest-service/api/v1/interest/accruals'

function fulfillAccruals(route: { fulfill: (r: object) => unknown }, available: boolean) {
  if (!available) {
    // classifyBffFailure maps 502 + upstream_unreachable → 'unreachable'
    // (a plain 503 with another error body lands on the generic 'error' kind).
    return route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"upstream_unreachable"}' })
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ACCRUALS) })
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last accrual snapshot during an outage and recovers', async ({ page }) => {
  test.setTimeout(60_000)
  let available = true
  let requests = 0
  await page.route(ACCRUALS_URL, route => {
    requests += 1
    return fulfillAccruals(route, available)
  })

  await page.goto('/interest')
  await expect(page.getByRole('heading', { name: /Úrokové výpočty|Interest Calculations/ })).toBeVisible()
  await expect(page.getByText(/42[.,]5041/)).toBeVisible()
  await expect(page.getByText('ACCRUING', { exact: true })).toBeVisible()
  await expect(page.getByText('CAPITALIZED', { exact: true })).toBeVisible()

  // Outage on refresh: 'unreachable' is a wake kind, so useServiceResource polls
  // 3 × 4s before settling — the retained snapshot stays on screen the whole time.
  available = false
  await page.getByRole('button', { name: /Obnovit úrokové záznamy|Refresh interest records/ }).click()
  await expect(page.getByText(/Obnovení selhalo — zobrazuji poslední úspěšný snapshot|Refresh failed — showing the last successful snapshot/)).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/42[.,]5041/)).toBeVisible()
  await expect(page.getByText(/Žádné výsledky pro zadaný filtr|No results for the applied filter/)).toHaveCount(0)

  // Recovery: a successful reload clears the staleness banner.
  available = true
  await page.getByRole('button', { name: /Zkusit znovu načíst úrokové záznamy|Retry loading interest records/ }).click()
  await expect(page.getByText(/Obnovení selhalo — zobrazuji poslední úspěšný snapshot|Refresh failed — showing the last successful snapshot/)).toBeHidden()
  await expect(page.getByText(/42[.,]5041/)).toBeVisible()
  expect(requests).toBeGreaterThanOrEqual(4)
})

test('first-load outage shows the unavailable state and the retry recovers', async ({ page }) => {
  test.setTimeout(60_000)
  let available = false
  await page.route(ACCRUALS_URL, route => fulfillAccruals(route, available))

  await page.goto('/interest')
  // Wake retries (3 × 4s) must elapse before the outage state settles; the
  // service-status badge in the header renders the same copy, hence .first().
  await expect(page.getByText(/[Ii]nterest-service neodpovídá|[Ii]nterest-service is not responding/).first()).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(/42[.,]5041/)).toHaveCount(0)

  available = true
  await page.getByRole('button', { name: /Zkusit znovu načíst|Retry loading/ }).click()
  await expect(page.getByText(/42[.,]5041/)).toBeVisible()
  await expect(page.getByText(/[Ii]nterest-service neodpovídá|[Ii]nterest-service is not responding/)).toHaveCount(0)
})

test('search filters by account, status and day count, and reports an empty filter', async ({ page }) => {
  await page.route(ACCRUALS_URL, route => fulfillAccruals(route, true))

  await page.goto('/interest')
  const search = page.getByRole('textbox', { name: /Hledat v úrokových výpočtech|Search interest calculations/ })

  await search.fill('ACT_360')
  await expect(page.getByText(/7[.,]5000/)).toBeVisible()
  await expect(page.getByText(/42[.,]5041/)).toHaveCount(0)

  await search.fill('CAPITALIZED')
  await expect(page.getByText(/41[.,]9178/)).toBeVisible()
  await expect(page.getByText(/42[.,]5041/)).toHaveCount(0)

  await search.fill('22222222')
  await expect(page.getByText('REVERSED', { exact: true })).toBeVisible()
  await expect(page.getByText('ACCRUING', { exact: true })).toHaveCount(0)

  await search.fill('no-such-account')
  await expect(page.getByText(/Žádné výsledky pro zadaný filtr|No results for the applied filter/)).toBeVisible()
})

test('a forbidden response denies access without rendering previously loaded rows', async ({ page }) => {
  let forbidden = false
  await page.route(ACCRUALS_URL, route => {
    if (forbidden) {
      return route.fulfill({ status: 403, contentType: 'application/json', body: '{"error":"forbidden"}' })
    }
    return fulfillAccruals(route, true)
  })

  await page.goto('/interest')
  await expect(page.getByText(/42[.,]5041/)).toBeVisible()

  forbidden = true
  await page.getByRole('button', { name: /Obnovit úrokové záznamy|Refresh interest records/ }).click()
  // The BFF contract maps both 401 and 403 to 'unauthorized' (classifyBffFailure);
  // the page hides the snapshot behind the unauthorized block.
  await expect(page.getByText(/Vypršela relace|Session expired/)).toBeVisible()
  await expect(page.getByText(/42[.,]5041/)).toHaveCount(0)
})

for (const language of ['en', 'cs'] as const) {
  test(`accrual totals and stat cards follow the locale (${language})`, async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'openbank-admin-lang', value: language, url: baseURL! }])
    await page.route(ACCRUALS_URL, route => fulfillAccruals(route, true))

    await page.goto('/interest')
    const labels = language === 'cs'
      ? [/Záznamy celkem/, /Akruuje/, /Kapitalizováno/, /Celkem naakruováno/]
      : [/Total records/, /Accruing/, /Capitalised/, /Total accrued/]
    for (const label of labels) {
      await expect(page.getByText(label)).toBeVisible()
    }
    // One currency in the fixture, so the total is a number, not "Multiple currencies".
    await expect(page.getByText(/91[.,]9219/)).toBeVisible()
  })
}
