// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps product editing modal, recoverable and focus-safe', async ({ page }) => {
  let posts = 0
  let finishSave: (() => void) | undefined
  const saved = new Promise<void>(resolve => { finishSave = resolve })

  await page.route('**/api/svc/product-catalog/api/v1/products', async route => {
    const request = route.request()
    if (request.method() === 'GET') {
      return route.fulfill({ contentType: 'application/json', body: '[]' })
    }

    posts += 1
    expect(request.method()).toBe('POST')
    expect(await request.postDataJSON()).toMatchObject({ code: 'SAFE_CURRENT', name: 'Safe Current', currency: 'EUR' })
    if (posts === 1) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"catalog unavailable"}' })
    }
    await saved
    return route.fulfill({ status: 201, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/product-catalog')
  const create = page.getByRole('button', { name: 'Create new product' })
  await create.click()

  let dialog = page.getByRole('dialog', { name: 'New Product' })
  await expect(dialog.getByRole('button', { name: 'Close product editor' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(create).toBeFocused()

  await create.click()
  dialog = page.getByRole('dialog', { name: 'New Product' })
  await dialog.getByLabel('Code *').fill('SAFE_CURRENT')
  await dialog.getByLabel('Name *').fill('Safe Current')
  await dialog.getByRole('button', { name: 'Save Product' }).click()
  await expect(dialog.getByRole('alert')).toContainText('catalog unavailable')
  await expect(dialog).toBeVisible()

  await dialog.getByRole('button', { name: 'Save Product' }).click()
  await expect.poll(() => posts).toBe(2)
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  finishSave?.()

  await expect(dialog).toBeHidden()
  await expect(create).toBeFocused()
})
