// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('server render and language toggle keep the document language synchronized', async ({ page, context, baseURL }) => {
  if (!baseURL) throw new Error('Playwright baseURL is required')
  await context.addCookies([{
    name: 'openbank-admin-lang',
    value: 'cs',
    url: baseURL,
    sameSite: 'Lax',
  }])

  const serverResponse = await page.request.get('/auth/login')
  expect(await serverResponse.text()).toMatch(/<html[^>]*lang="cs"/)

  await page.goto('/auth/login')

  await expect(page.locator('html')).toHaveAttribute('lang', 'cs')
  await expect(page.getByRole('heading', { name: 'Vítejte zpět' })).toBeVisible()

  await page.getByRole('button', { name: 'Switch to English' }).click()

  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
})

test('language changes refresh cookie-localized server content', async ({ page, context, baseURL }) => {
  if (!baseURL) throw new Error('Playwright baseURL is required')
  await signInAsOperator(context, baseURL)
  await context.addCookies([{
    name: 'openbank-admin-lang',
    value: 'en',
    url: baseURL,
    sameSite: 'Lax',
  }])

  await page.goto('/docs/sensors')

  await expect(page.getByRole('heading', { name: 'Customer-app sensors' })).toBeVisible()
  await page.getByRole('button', { name: 'Switch to Czech' }).click()

  await expect(page.locator('html')).toHaveAttribute('lang', 'cs')
  await expect.poll(async () => {
    const cookie = (await context.cookies()).find(item => item.name === 'openbank-admin-lang')
    return cookie?.value
  }).toBe('cs')

  await expect(page.getByRole('heading', { name: 'Senzory zákaznické aplikace' })).toBeVisible()

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('lang', 'cs')
  await expect(page.getByRole('heading', { name: 'Senzory zákaznické aplikace' })).toBeVisible()
})
