// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('registers a party responsively and retries the same command safely', async ({ page }) => {
  const idempotencyKeys: string[] = []
  const payloads: unknown[] = []
  let releaseFirstResponse: (() => void) | undefined
  await page.route('**/api/svc/party-service/api/v1/parties', async route => {
    const request = route.request()
    if (request.method() !== 'POST') {
      await route.continue()
      return
    }
    idempotencyKeys.push(request.headers()['idempotency-key'] ?? '')
    payloads.push(request.postDataJSON())
    if (idempotencyKeys.length === 1) {
      await new Promise<void>(resolve => { releaseFirstResponse = resolve })
      await route.fulfill({ status: 503, body: '' })
      return
    }
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'party-42' }),
    })
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/parties/new')

  const form = page.locator('form')
  const formGrid = form.locator(':scope > div').first()
  expect(await formGrid.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await expect(page.locator('#party-form-guidance')).toBeVisible()

  const submit = form.locator('button[type="submit"]')
  await submit.click()
  await expect(page.locator('#party-legal-name')).toBeFocused()
  expect(idempotencyKeys).toHaveLength(0)

  await page.locator('#party-legal-name').fill('Alena Nováková')
  await page.locator('#party-email').fill('alena.novakova@example.test')
  await page.locator('#party-phone').fill('+420 777 123 456')
  await page.locator('#party-address-line1').fill('Bankovní 42')
  await page.locator('#party-address-city').fill('Praha')
  await page.locator('#party-address-postal').fill('110 00')

  const firstSubmit = submit.click()
  await expect(submit).toBeDisabled()
  await expect(submit).toHaveAttribute('aria-busy', 'true')
  await expect.poll(() => Boolean(releaseFirstResponse)).toBe(true)
  releaseFirstResponse?.()
  await firstSubmit
  await expect(page.getByRole('alert').filter({ hasText: /selhalo|failed/i })).toBeVisible()
  await expect(submit).toBeEnabled()
  expect(idempotencyKeys).toHaveLength(1)
  expect(idempotencyKeys[0]).not.toBe('')

  await submit.click()
  await expect(page).toHaveURL(/\/parties\/party-42$/)
  expect(idempotencyKeys).toHaveLength(2)
  expect(idempotencyKeys[1]).toBe(idempotencyKeys[0])
  expect(payloads[1]).toEqual(payloads[0])
  expect(payloads[0]).toMatchObject({
    partyType: 'INDIVIDUAL',
    legalName: 'Alena Nováková',
    email: 'alena.novakova@example.test',
    address: { line1: 'Bankovní 42', city: 'Praha', postalCode: '110 00', countryCode: 'CZ' },
  })
})
