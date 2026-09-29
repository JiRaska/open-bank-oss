import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/fx/rates', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      cnb: { rates: [{ currencyCode: 'USD', amount: 1, rate: 21.5, validFor: '2026-09-16' }], syncedAt: '2026-09-16T10:00:00Z', error: null },
      ecb: { rates: [], syncedAt: null, error: null },
      fxService: { status: 'scaled_to_zero', rates: [], conversions: [] },
    }),
  }))
  await page.route('**/api/fx/history/*', route => route.fulfill({ contentType: 'application/json', body: '[]' }))
})

test('failed source check explains the failure while preserving visible rates', async ({ page }) => {
  let rateReads = 0
  let failRead = false
  await page.route('**/api/fx/rates', route => {
    rateReads += 1
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        cnb: !failRead
          ? { rates: [{ currencyCode: 'USD', amount: 1, rate: 21.5, validFor: '2026-09-16' }], syncedAt: '2026-09-16T10:00:00Z', error: null }
          : { rates: [], syncedAt: null, error: 'upstream timeout' },
        ecb: { rates: [], syncedAt: null, error: null },
        fxService: { status: 'scaled_to_zero', rates: [], conversions: [] },
      }),
    })
  })
  await page.route('**/api/fx/refresh', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ results: { cnb: { ok: false, error: 'upstream timeout' }, ecb: { ok: true, count: 1 } } }),
  }))

  await page.goto('/fx', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: /Kurzy ČNB|CNB rates/ }).click()
  await expect(page.getByText('USD', { exact: true }).first()).toBeVisible()
  const initialReads = rateReads
  failRead = true
  await page.getByRole('button', { name: /Stáhnout všechny kurzy FX|Fetch all FX rates/ }).click()
  await expect(page.getByRole('alert').filter({ hasText: /Některý požadovaný zdroj neodpověděl|A requested source did not respond/ })).toBeVisible()
  await expect.poll(() => rateReads).toBeGreaterThan(initialReads)
  await expect(page.getByText('upstream timeout')).toBeVisible()
  await expect(page.getByText('USD', { exact: true }).first()).toBeVisible()
})

test('expired session has a useful explanation and does not erase rates', async ({ page }) => {
  await page.route('**/api/fx/refresh', route => route.fulfill({
    status: 401, contentType: 'application/json', body: '{"error":"Unauthenticated"}',
  }))

  await page.goto('/fx', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: /Kurzy ČNB|CNB rates/ }).click()
  await expect(page.getByText('USD', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: /Stáhnout všechny kurzy FX|Fetch all FX rates/ }).click()
  await expect(page.getByRole('alert').filter({ hasText: /Ověření zdroje vyžaduje přihlášení|Checking the source requires sign-in/ })).toBeVisible()
  await expect(page.getByText('USD', { exact: true }).first()).toBeVisible()
})
