// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('the deployable image enforces auth and serves its stamped source and UI', async ({ page, context, baseURL }) => {
  const expectedSha = process.env.OPENBANK_IMAGE_TEST_SHA
  expect(expectedSha).toMatch(/^[0-9a-f]{7,40}$/)
  const guest = await context.request.get('/api/test-intelligence', { maxRedirects: 0 })
  expect([302, 303, 307, 308, 401]).toContain(guest.status())
  if (guest.status() !== 401) expect(guest.headers().location).toContain('/auth/login')

  await signInAsOperator(context, baseURL!)
  const build = await context.request.get('/api/build-info')
  expect(build.ok()).toBe(true)
  expect((await build.json()).gitSha).toBe(expectedSha)

  // No page.route mocks: prove standalone assets, middleware and the real BFF
  // are present in this image. Backend availability is a separate deployed probe.
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const assets: number[] = []
  page.on('response', response => {
    if (response.url().includes('/_next/static/')) assets.push(response.status())
  })
  await page.goto('/system/health')
  await expect(page.getByRole('main')).toBeVisible()
  await expect(page).toHaveURL(/\/system\/health/)
  await expect(page.getByRole('heading').first()).toBeVisible()
  expect(assets.length).toBeGreaterThan(0)
  expect(assets.every(status => status === 200 || status === 304)).toBe(true)
  expect(errors).toEqual([])
})
