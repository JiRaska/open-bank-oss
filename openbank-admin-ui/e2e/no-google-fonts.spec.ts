// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue: the operator shell fetched Plus Jakarta Sans / JetBrains Mono from Google Fonts on
// every render (CSS @import + <link rel="preconnect">). Only a real browser can prove the
// network tab is actually clean — a source grep proves the code no longer *says*
// fonts.googleapis.com/fonts.gstatic.com, not that nothing ends up requesting them (e.g. a
// browser-default DNS prefetch, or a stylesheet computed at runtime).

import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const GOOGLE_FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com']

function collectGoogleFontRequests(page: import('@playwright/test').Page): string[] {
  const seen: string[] = []
  page.on('request', req => {
    const url = req.url()
    if (GOOGLE_FONT_HOSTS.some(host => url.includes(host))) seen.push(url)
  })
  return seen
}

test('the public privacy page never requests Google Fonts', async ({ page }) => {
  const seen = collectGoogleFontRequests(page)
  await page.goto('/privacy')
  await expect(page.locator('main').first()).toBeVisible()
  expect(seen).toEqual([])
})

test('the authenticated operator shell never requests Google Fonts', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  const seen = collectGoogleFontRequests(page)
  await page.goto('/dashboard')
  await expect(page.locator('main').first()).toBeVisible()
  expect(seen).toEqual([])
})
